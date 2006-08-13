/*
 * Copyright 2004-2006 the Seasar Foundation and the Others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, 
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.seasar.diigu.eclipse.operation;

import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.seasar.diigu.ParameterNameEnhancer;
import org.seasar.diigu.eclipse.DiiguPlugin;
import org.seasar.diigu.eclipse.builder.DiiguNature;
import org.seasar.diigu.eclipse.nls.Messages;
import org.seasar.diigu.eclipse.util.ArrayUtil;
import org.seasar.diigu.eclipse.util.JavaProjectClassLoader;

/**
 * @author taichi
 * 
 */
public class NameEnhanceJob extends WorkspaceJob {

    private IProject project;

    private IWorkspaceRunnable runnable;

    public NameEnhanceJob(String name, final IProject project) {
        super(name);
        setPriority(Job.BUILD);
        this.project = project;
        this.runnable = new IWorkspaceRunnable() {
            public void run(final IProgressMonitor monitor)
                    throws CoreException {
                project.accept(new IResourceVisitor() {
                    public boolean visit(IResource resource)
                            throws CoreException {
                        enhance(resource, monitor);
                        return true;
                    }
                });
            }
        };
    }

    public NameEnhanceJob(String name, final IResourceDelta delta) {
        super(name);
        setPriority(Job.BUILD);
        this.project = delta.getResource().getProject();
        this.runnable = new IWorkspaceRunnable() {
            public void run(final IProgressMonitor monitor)
                    throws CoreException {
                delta.accept(new IResourceDeltaVisitor() {
                    public boolean visit(IResourceDelta delta)
                            throws CoreException {
                        final int MASK = IResourceDelta.ADDED
                                | IResourceDelta.CHANGED;
                        if ((MASK & delta.getKind()) != 0) {
                            enhance(delta.getResource(), monitor);
                        }
                        return true;
                    }
                });
            }
        };
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.core.resources.WorkspaceJob#runInWorkspace(org.eclipse.core.runtime.IProgressMonitor)
     */
    public IStatus runInWorkspace(IProgressMonitor monitor)
            throws CoreException {
        this.runnable.run(monitor);
        return Status.OK_STATUS;
    }

    void enhance(IResource resource, IProgressMonitor monitor)
            throws CoreException {
        if (resource instanceof IFile && resource.getName().endsWith(".java")) {
            try {
                if (monitor == null) {
                    monitor = new NullProgressMonitor();
                }

                ICompilationUnit unit = JavaCore
                        .createCompilationUnitFrom((IFile) resource);
                enhance(unit, monitor);
            } catch (CoreException e) {
                throw e;
            } catch (Exception e) {
                e.printStackTrace();
                IStatus status = new Status(IStatus.ERROR,
                        DiiguPlugin.PLUGIN_ID, 0, e.getMessage(), e);
                throw new CoreException(status);
            }
        }
    }

    protected void enhance(ICompilationUnit unit, IProgressMonitor monitor)
            throws CoreException, Exception {
        monitor.beginTask(Messages.ENHANCE_BEGIN, 5);
        try {
            IPath outpath = unit.getJavaProject().getOutputLocation();
            // FIXME : クラスローダは毎回作らなければならないが、URL[]を毎回作るのは、どうなのかな…。
            // そもそも、変更の入ったクラスのローダは作り直さないといけないけど、
            // 参照ライブラリの類をロードする為のローダは、毎回作り直す必然性が無いんじゃね？
            // どっかでイベント拾って作る方が、体感速度があがると思われ。
            JavaProjectClassLoader loader = new JavaProjectClassLoader(unit
                    .getJavaProject());
            monitor.worked(1);
            IProgressMonitor submonitor = new SubProgressMonitor(monitor, 3);
            IType[] types = unit.getAllTypes();
            submonitor.beginTask(Messages.ENHANCE_PROCEED, types.length);
            DiiguNature nature = DiiguNature.getInstance(this.project);
            Pattern ptn = null;
            if (nature != null) {
                ptn = nature.getSelectExpression();
            }
            for (int i = 0; i < types.length; i++) {
                IType type = types[i];
                String typename = type.getFullyQualifiedName();

                if (ptn != null && ptn.matcher(typename).matches()) {
                    submonitor.subTask(typename);
                    IPath path = outpath.append(type.getFullyQualifiedName()
                            .replace('.', '/')
                            + ".class");
                    IResource resource = this.project.getParent().getFile(path);
                    ParameterNameEnhancer enhancer = new ParameterNameEnhancer(
                            type.getFullyQualifiedName(), loader);

                    if (enhanceClassFile(type, enhancer)) {
                        enhancer.save();
                        resource.refreshLocal(IResource.DEPTH_ONE, monitor);
                    }
                    submonitor.worked(1);
                    if (submonitor.isCanceled()) {
                        break;
                    }
                }
            }
            submonitor.done();
            monitor.setTaskName(Messages.ENHANCE_END);
            monitor.worked(1);
        } finally {
            monitor.done();
        }
    }

    protected boolean enhanceClassFile(IType type,
            ParameterNameEnhancer enhancer) throws CoreException {
        boolean dirty = false;
        IMethod[] methods = type.getMethods();
        for (int i = 0; i < methods.length; i++) {
            dirty |= enhanceMethod(methods[i], enhancer);
        }
        return dirty;
    }

    protected boolean enhanceMethod(IMethod method,
            ParameterNameEnhancer enhancer) throws CoreException {
        if (method.getNumberOfParameters() < 1) {
            return false;
        }
        try {
            String[] typeSignatures = method.getParameterTypes();
            String[] parameterTypes = new String[typeSignatures.length];
            for (int i = 0; i < typeSignatures.length; i++) {
                parameterTypes[i] = getResolvedTypeName(typeSignatures[i],
                        method.getDeclaringType());
            }
            String[] parameterNames = method.getParameterNames();
            if (method.isConstructor()) {
                IType me = method.getDeclaringType();
                IType around = me.getDeclaringType();
                if (Flags.isStatic(me.getFlags()) == false && around != null
                        && around.isClass()) {
                    String[] newtypes = { resolveType(me, 0, around
                            .getElementName()) };
                    parameterTypes = (String[]) ArrayUtil.add(newtypes,
                            parameterTypes);
                    String[] newnames = { "this" };
                    parameterNames = (String[]) ArrayUtil.add(newnames,
                            parameterNames);
                }
                enhancer.setConstructorParameterNames(parameterTypes,
                        parameterNames);
            } else {
                String methodName = method.getElementName();
                enhancer.setMethodParameterNames(methodName, parameterTypes,
                        parameterNames);
            }
            return true;
        } catch (CoreException e) {
            DiiguPlugin.log(e);
            throw e;
        } catch (RuntimeException e) {
            String msg = Messages.bind(Messages.ENHANCE_ERROR, method
                    .getDeclaringType(), method);
            IStatus status = new Status(IStatus.ERROR, DiiguPlugin.PLUGIN_ID,
                    IStatus.ERROR, msg, e);
            DiiguPlugin.getDefault().getLog().log(status);
            return false;
        }
    }

    public static String getResolvedTypeName(String typeSignature, IType type)
            throws JavaModelException {
        int count = Signature.getArrayCount(typeSignature);
        if (Signature.C_UNRESOLVED == typeSignature.charAt(count)) {
            String name = null;
            if (0 < count) {
                name = typeSignature.substring(count + 1, typeSignature
                        .indexOf(';'));
            } else {
                name = Signature.toString(typeSignature);
            }
            return resolveType(type, count, name);
        } else {
            return Signature.toString(typeSignature);
        }
    }

    /**
     * @param type
     * @param count
     * @param name
     * @return
     * @throws JavaModelException
     */
    private static String resolveType(IType type, int count, String name)
            throws JavaModelException {
        String[][] resolvedNames = type.resolveType(name);
        if (resolvedNames != null && resolvedNames.length > 0) {
            StringBuffer stb = new StringBuffer();
            String pkg = resolvedNames[0][0];
            if (pkg != null && 0 < pkg.length()) {
                stb.append(resolvedNames[0][0]);
                stb.append('.');
            }
            stb.append(resolvedNames[0][1].replace('.', '$'));
            for (int i = 0; i < count; i++) {
                stb.append("[]");
            }
            return stb.toString();
        }
        return "";
    }

}