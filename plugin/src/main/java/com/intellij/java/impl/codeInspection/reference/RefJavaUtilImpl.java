/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * User: anna
 * Date: 21-Dec-2007
 */
package com.intellij.java.impl.codeInspection.reference;

import com.intellij.java.analysis.codeInspection.reference.*;
import com.intellij.java.analysis.impl.codeInspection.reference.RefClassImpl;
import com.intellij.java.analysis.impl.codeInspection.reference.RefJavaElementImpl;
import com.intellij.java.analysis.impl.codeInspection.reference.RefMethodImpl;
import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.util.MethodSignatureUtil;
import com.intellij.java.language.psi.util.PsiUtil;
import com.intellij.java.language.util.VisibilityUtil;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ServiceImpl;
import consulo.language.editor.impl.inspection.reference.RefElementImpl;
import consulo.language.editor.inspection.localize.InspectionLocalize;
import consulo.language.editor.inspection.reference.*;
import consulo.language.psi.PsiElement;
import consulo.language.psi.scope.GlobalSearchScope;
import consulo.language.psi.util.PsiTreeUtil;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;

@Singleton
@ServiceImpl
public class RefJavaUtilImpl extends RefJavaUtil {

  @Override
  public void addReferences(final PsiModifierListOwner psiFrom, final RefJavaElement ref, @Nullable PsiElement findIn) {
    final RefJavaElementImpl refFrom = (RefJavaElementImpl) ref;
    if (findIn != null) {
      findIn.accept(
          new JavaRecursiveElementWalkingVisitor() {
            @Override
            public void visitReferenceElement(@Nonnull PsiJavaCodeReferenceElement reference) {
            }

            @Override
            @RequiredReadAction
            public void visitReferenceExpression(PsiReferenceExpression expression) {
              visitElement(expression);

              PsiElement psiResolved = expression.resolve();

              if (psiResolved instanceof PsiModifierListOwner) {
                if (isDeprecated(psiResolved)) refFrom.setUsesDeprecatedApi(true);
              }

              RefElement refResolved = refFrom.getRefManager().getReference(psiResolved);
              refFrom.addReference(
                  refResolved, psiResolved, psiFrom, PsiUtil.isAccessedForWriting(expression),
                  PsiUtil.isAccessedForReading(expression), expression
              );

              if (refResolved instanceof RefMethod) {
                updateRefMethod(psiResolved, refResolved, expression, psiFrom, refFrom);
              }
            }


            @Override
            public void visitEnumConstant(@Nonnull PsiEnumConstant enumConstant) {
              super.visitEnumConstant(enumConstant);
              processNewLikeConstruct(enumConstant.resolveConstructor(), enumConstant.getArgumentList());
            }

            @Override
            public void visitNewExpression(@Nonnull PsiNewExpression newExpr) {
              super.visitNewExpression(newExpr);
              PsiMethod psiConstructor = newExpr.resolveConstructor();
              final PsiExpressionList argumentList = newExpr.getArgumentList();

              RefMethod refConstructor = processNewLikeConstruct(psiConstructor, argumentList);

              if (refConstructor == null) {  // No explicit constructor referenced. Should use default one.
                PsiType newType = newExpr.getType();
                if (newType instanceof PsiClassType) {
                  processClassReference(PsiUtil.resolveClassInType(newType), refFrom, psiFrom, true);
                }
              }
            }

            @Nullable
            private RefMethod processNewLikeConstruct(final PsiMethod psiConstructor, final PsiExpressionList argumentList) {
              if (psiConstructor != null) {
                if (isDeprecated(psiConstructor)) refFrom.setUsesDeprecatedApi(true);
              }

              RefMethodImpl refConstructor = (RefMethodImpl) refFrom.getRefManager().getReference(
                  psiConstructor
              );
              refFrom.addReference(refConstructor, psiConstructor, psiFrom, false, true, null);

              if (argumentList != null) {
                PsiExpression[] psiParams = argumentList.getExpressions();
                for (PsiExpression param : psiParams) {
                  param.accept(this);
                }

                if (refConstructor != null) {
                  refConstructor.updateParameterValues(psiParams);
                }
              }
              return refConstructor;
            }

            @Override
            public void visitAnonymousClass(PsiAnonymousClass psiClass) {
              super.visitAnonymousClass(psiClass);
              RefClassImpl refClass = (RefClassImpl) refFrom.getRefManager().getReference(psiClass);
              refFrom.addReference(refClass, psiClass, psiFrom, false, true, null);
            }

            @Override
            public void visitReturnStatement(PsiReturnStatement statement) {
              super.visitReturnStatement(statement);

              if (refFrom instanceof RefMethodImpl refMethod) {
                refMethod.updateReturnValueTemplate(statement.getReturnValue());
              }
            }

            @Override
            public void visitClassObjectAccessExpression(PsiClassObjectAccessExpression expression) {
              super.visitClassObjectAccessExpression(expression);
              final PsiTypeElement operand = expression.getOperand();
              final PsiType type = operand.getType();
              if (type instanceof PsiClassType classType) {
                processClassReference(classType.resolve(), refFrom, psiFrom, false);
              }
            }

            private void processClassReference(
              final PsiClass psiClass,
              final RefJavaElementImpl refFrom,
              final PsiModifierListOwner psiFrom,
              boolean defaultConstructorOnly
            ) {
              if (psiClass != null) {
                RefClassImpl refClass = (RefClassImpl) refFrom.getRefManager().getReference(psiClass);

                if (refClass != null) {
                  boolean hasConstructorsMarked = false;

                  if (defaultConstructorOnly) {
                    RefMethodImpl refDefaultConstructor = (RefMethodImpl) refClass.getDefaultConstructor();
                    if (refDefaultConstructor != null && !(refDefaultConstructor instanceof RefImplicitConstructor)) {
                      refDefaultConstructor.addInReference(refFrom);
                      refFrom.addOutReference(refDefaultConstructor);
                      hasConstructorsMarked = true;
                    }
                  } else {
                    for (RefMethod cons : refClass.getConstructors()) {
                      if (cons instanceof RefImplicitConstructor) continue;
                      ((RefMethodImpl) cons).addInReference(refFrom);
                      refFrom.addOutReference(cons);
                      hasConstructorsMarked = true;
                    }
                  }

                  if (!hasConstructorsMarked) {
                    refFrom.addReference(refClass, psiClass, psiFrom, false, true, null);
                  }
                }
              }
            }
          }
      );
    }
  }

  private void updateRefMethod(
    PsiElement psiResolved,
    RefElement refResolved,
    PsiElement refExpression,
    final PsiElement psiFrom,
    final RefElement refFrom
  ) {
    PsiMethod psiMethod = (PsiMethod) psiResolved;
    RefMethodImpl refMethod = (RefMethodImpl) refResolved;

    PsiMethodCallExpression call = PsiTreeUtil.getParentOfType(
        refExpression,
        PsiMethodCallExpression.class
    );
    if (call != null) {
      PsiType returnType = psiMethod.getReturnType();
      if (!psiMethod.isConstructor() && !PsiType.VOID.equals(returnType)) {
        if (!(call.getParent() instanceof PsiExpressionStatement)) {
          refMethod.setReturnValueUsed(true);
        }

        addTypeReference(psiFrom, returnType, refFrom.getRefManager());
      }

      PsiExpressionList argumentList = call.getArgumentList();
      if (argumentList.getExpressions().length > 0) {
        refMethod.updateParameterValues(argumentList.getExpressions());
      }

      final PsiExpression psiExpression = call.getMethodExpression().getQualifierExpression();
      if (psiExpression != null) {
        final PsiType usedType = psiExpression.getType();
        if (usedType != null) {
          final String fqName = psiMethod.getContainingClass().getQualifiedName();
          if (fqName != null) {
            final PsiClassType methodOwnerType = JavaPsiFacade.getInstance(call.getProject()).getElementFactory()
                .createTypeByFQClassName(fqName, GlobalSearchScope.allScope(psiMethod.getProject()));
            if (!usedType.equals(methodOwnerType)) {
              refMethod.setCalledOnSubClass(true);
            }
          }
        }
      }
    }
  }


  @Override
  public RefClass getTopLevelClass(RefElement refElement) {
    RefEntity refParent = refElement.getOwner();

    while (refParent != null && refParent instanceof RefElement refParentElement && !(refParent instanceof RefFile)) {
      refElement = refParentElement;
      refParent = refParent.getOwner();
    }

    return (RefClass) refElement;
  }

  @Override
  public boolean isInheritor(RefClass subClass, RefClass superClass) {
    if (subClass == superClass) return true;

    for (RefClass baseClass : subClass.getBaseClasses()) {
      if (isInheritor(baseClass, superClass)) return true;
    }

    return false;
  }

  @Override
  @Nullable
  public String getPackageName(RefEntity refEntity) {
    if (refEntity instanceof RefProject) {
      return null;
    }
    RefPackage refPackage = getPackage(refEntity);

    return refPackage == null ? InspectionLocalize.inspectionReferenceDefaultPackage().get() : refPackage.getQualifiedName();
  }

  @Override
  public String getAccessModifier(PsiModifierListOwner psiElement) {
    if (psiElement instanceof PsiParameter) {
      return PsiModifier.PACKAGE_LOCAL;
    }

    PsiModifierList list = psiElement.getModifierList();
    String result = PsiModifier.PACKAGE_LOCAL;

    if (list != null) {
      if (list.hasModifierProperty(PsiModifier.PRIVATE)) {
        result = PsiModifier.PRIVATE;
      } else if (list.hasModifierProperty(PsiModifier.PROTECTED)) {
        result = PsiModifier.PROTECTED;
      } else if (list.hasModifierProperty(PsiModifier.PUBLIC)) {
        result = PsiModifier.PUBLIC;
      } else if (psiElement.getParent() instanceof PsiClass parentClass) {
        PsiClass ownerClass = parentClass;
        if (ownerClass.isInterface()) {
          result = PsiModifier.PUBLIC;
        }
      }
    }

    return result;
  }

  @Override
  @Nullable
  public RefClass getOwnerClass(RefManager refManager, PsiElement psiElement) {
    while (psiElement != null && !(psiElement instanceof PsiClass)) {
      psiElement = psiElement.getParent();
    }

    return psiElement != null ? (RefClass) refManager.getReference(psiElement) : null;
  }

  @Override
  @Nullable
  public RefClass getOwnerClass(RefElement refElement) {
    RefEntity parent = refElement.getOwner();

    while (!(parent instanceof RefClass) && parent instanceof RefElement) {
      parent = parent.getOwner();
    }

    return parent instanceof RefClass refClass ? refClass : null;
  }


  @Override
  public boolean isMethodOnlyCallsSuper(PsiMethod method) {
    boolean hasStatements = false;
    PsiCodeBlock body = method.getBody();
    if (body != null) {
      PsiStatement[] statements = body.getStatements();
      for (PsiStatement statement : statements) {
        boolean isCallToSameSuper = false;
        if (statement instanceof PsiExpressionStatement expressionStatement) {
          isCallToSameSuper = isCallToSuperMethod(expressionStatement.getExpression(), method);
        } else if (statement instanceof PsiReturnStatement returnStatement) {
          PsiExpression expression = returnStatement.getReturnValue();
          isCallToSameSuper = expression == null || isCallToSuperMethod(expression, method);
        }

        hasStatements = true;
        if (isCallToSameSuper) continue;

        return false;
      }
    }

    if (hasStatements) {
      final PsiMethod[] superMethods = method.findSuperMethods();
      for (PsiMethod superMethod : superMethods) {
        if (VisibilityUtil.compare(VisibilityUtil.getVisibilityModifier(superMethod.getModifierList()),
            VisibilityUtil.getVisibilityModifier(method.getModifierList())) > 0) return false;
      }
    }
    return hasStatements;
  }

  @Override
  @RequiredReadAction
  public boolean isCallToSuperMethod(PsiExpression expression, PsiMethod method) {
    if (expression instanceof PsiMethodCallExpression methodCall) {
      if (methodCall.getMethodExpression().getQualifierExpression() instanceof PsiSuperExpression) {
        PsiMethod superMethod = (PsiMethod) methodCall.getMethodExpression().resolve();
        if (superMethod == null || !MethodSignatureUtil.areSignaturesEqual(method, superMethod)) return false;
        PsiExpression[] args = methodCall.getArgumentList().getExpressions();
        PsiParameter[] parms = method.getParameterList().getParameters();

        for (int i = 0; i < args.length; i++) {
          PsiExpression arg = args[i];
          if (!(arg instanceof PsiReferenceExpression)) return false;
          if (!parms[i].equals(((PsiReferenceExpression) arg).resolve())) return false;
        }

        return true;
      }
    }

    return false;
  }

  @Override
  public int compareAccess(String a1, String a2) {
    int i1 = getAccessNumber(a1);
    int i2 = getAccessNumber(a2);

    if (i1 == i2) return 0;
    if (i1 < i2) return -1;
    return 1;
  }

  @SuppressWarnings("StringEquality")
  private static int getAccessNumber(String a) {
    if (a == PsiModifier.PRIVATE) {
      return 0;
    } else if (a == PsiModifier.PACKAGE_LOCAL) {
      return 1;
    } else if (a == PsiModifier.PROTECTED) {
      return 2;
    } else if (a == PsiModifier.PUBLIC) return 3;

    return -1;
  }

  @Override
  public void setAccessModifier(RefJavaElement refElement, String newAccess) {
    ((RefJavaElementImpl) refElement).setAccessModifier(newAccess);
  }

  @Override
  public void setIsStatic(RefJavaElement refElement, boolean isStatic) {
    ((RefJavaElementImpl) refElement).setIsStatic(isStatic);
  }

  @Override
  public void setIsFinal(RefJavaElement refElement, boolean isFinal) {
    ((RefJavaElementImpl) refElement).setIsFinal(isFinal);
  }

  @Override
  public void addTypeReference(PsiElement psiElement, PsiType psiType, RefManager refManager) {
    RefClass ownerClass = getOwnerClass(refManager, psiElement);

    if (ownerClass != null) {
      psiType = psiType.getDeepComponentType();

      if (psiType instanceof PsiClassType) {
        PsiClass psiClass = PsiUtil.resolveClassInType(psiType);
        if (psiClass != null && refManager.belongsToScope(psiClass)) {
          RefClassImpl refClass = (RefClassImpl) refManager.getReference(psiClass);
          if (refClass != null) {
            refClass.addTypeReference(ownerClass);
          }
        }
      }
    }
  }
}
