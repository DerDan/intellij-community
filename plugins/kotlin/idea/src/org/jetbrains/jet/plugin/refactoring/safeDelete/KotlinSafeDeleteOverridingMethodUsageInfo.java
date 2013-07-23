/*
 * Copyright 2010-2013 JetBrains s.r.o.
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

package org.jetbrains.jet.plugin.refactoring.safeDelete;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.refactoring.safeDelete.usageInfo.SafeDeleteCustomUsageInfo;
import com.intellij.refactoring.safeDelete.usageInfo.SafeDeleteUsageInfo;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.jet.asJava.LightClassUtil;
import org.jetbrains.jet.lang.psi.JetNamedFunction;

public class KotlinSafeDeleteOverridingMethodUsageInfo extends SafeDeleteUsageInfo implements SafeDeleteCustomUsageInfo {
    public KotlinSafeDeleteOverridingMethodUsageInfo(PsiElement overridingMethod, PsiElement method) {
        super(toPsiMethod(overridingMethod), toPsiMethod(method));
    }

    protected static PsiMethod toPsiMethod(PsiElement element) {
        if (element instanceof JetNamedFunction) {
            element = LightClassUtil.getLightClassMethod((JetNamedFunction) element);
        }
        return element instanceof PsiMethod ? (PsiMethod) element : null;
    }

    public PsiMethod getOverridingMethod() {
        return toPsiMethod(getElement());
    }

    @Override
    public void performRefactoring() throws IncorrectOperationException {
        PsiElement element = getElement();
        if (element != null) {
            element.delete();
        }
    }
}
