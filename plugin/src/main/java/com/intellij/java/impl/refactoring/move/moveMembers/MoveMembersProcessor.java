/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.intellij.java.impl.refactoring.move.moveMembers;

import com.intellij.java.impl.refactoring.HelpID;
import com.intellij.java.impl.refactoring.util.RefactoringConflictsUtil;
import com.intellij.java.language.psi.*;
import com.intellij.java.language.util.VisibilityUtil;
import consulo.language.editor.refactoring.BaseRefactoringProcessor;
import consulo.language.editor.refactoring.event.RefactoringElementListener;
import consulo.language.editor.refactoring.localize.RefactoringLocalize;
import consulo.language.editor.refactoring.move.MoveCallback;
import consulo.language.editor.refactoring.move.MoveHandler;
import consulo.language.editor.refactoring.move.MoveMemberViewDescriptor;
import consulo.language.editor.refactoring.util.CommonRefactoringUtil;
import consulo.language.editor.util.EditorHelper;
import consulo.language.psi.*;
import consulo.language.psi.scope.GlobalSearchScope;
import consulo.language.psi.search.ReferencesSearch;
import consulo.language.psi.util.PsiTreeUtil;
import consulo.language.util.IncorrectOperationException;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.usage.MoveRenameUsageInfo;
import consulo.usage.UsageInfo;
import consulo.usage.UsageViewDescriptor;
import consulo.usage.UsageViewUtil;
import consulo.util.collection.ContainerUtil;
import consulo.util.collection.MultiMap;
import consulo.util.lang.ref.SimpleReference;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.*;

/**
 * @author Jeka
 * @since 2001-09-11
 */
public class MoveMembersProcessor extends BaseRefactoringProcessor {
    private static final Logger LOG = Logger.getInstance(MoveMembersProcessor.class);

    private PsiClass myTargetClass;
    private final Set<PsiMember> myMembersToMove = new LinkedHashSet<PsiMember>();
    private final MoveCallback myMoveCallback;
    private final boolean myOpenInEditor;
    private String myNewVisibility; // "null" means "as is"
    private String myCommandName = MoveMembersImpl.REFACTORING_NAME;
    private MoveMembersOptions myOptions;

    public MoveMembersProcessor(Project project, MoveMembersOptions options) {
        this(project, null, options);
    }

    public MoveMembersProcessor(Project project, @Nullable MoveCallback moveCallback, MoveMembersOptions options) {
        this(project, moveCallback, options, false);
    }

    public MoveMembersProcessor(Project project, @Nullable MoveCallback moveCallback, MoveMembersOptions options, boolean openInEditor) {
        super(project);
        myMoveCallback = moveCallback;
        myOpenInEditor = openInEditor;
        setOptions(options);
    }

    protected String getCommandName() {
        return myCommandName;
    }

    private void setOptions(MoveMembersOptions dialog) {
        myOptions = dialog;

        PsiMember[] members = dialog.getSelectedMembers();
        myMembersToMove.clear();
        ContainerUtil.addAll(myMembersToMove, members);

        setCommandName(members);

        final String targetClassName = dialog.getTargetClassName();
        myTargetClass = JavaPsiFacade.getInstance(myProject).findClass(targetClassName, GlobalSearchScope.projectScope(myProject));
        LOG.assertTrue(myTargetClass != null, "target class: " + targetClassName);
        myNewVisibility = dialog.getMemberVisibility();
    }

    private void setCommandName(final PsiMember[] members) {
        StringBuilder commandName = new StringBuilder();
        commandName.append(MoveHandler.REFACTORING_NAME);
        commandName.append(" ");
        boolean first = true;
        for (PsiMember member : members) {
            if (!first) {
                commandName.append(", ");
            }
            commandName.append(UsageViewUtil.getType(member));
            commandName.append(' ');
            commandName.append(UsageViewUtil.getShortName(member));
            first = false;
        }

        myCommandName = commandName.toString();
    }

    @Nonnull
    protected UsageViewDescriptor createUsageViewDescriptor(@Nonnull UsageInfo[] usages) {
        return new MoveMemberViewDescriptor(PsiUtilCore.toPsiElementArray(myMembersToMove));
    }

    @Nonnull
    protected UsageInfo[] findUsages() {
        final List<UsageInfo> usagesList = new ArrayList<UsageInfo>();
        for (PsiMember member : myMembersToMove) {
            for (PsiReference psiReference : ReferencesSearch.search(member)) {
                PsiElement ref = psiReference.getElement();
                final MoveMemberHandler handler = MoveMemberHandler.forLanguage(ref.getLanguage());
                MoveMembersUsageInfo usage = null;
                if (handler != null && myTargetClass != null) {
                    usage = handler.getUsage(member, psiReference, myMembersToMove, myTargetClass);
                }
                if (usage != null) {
                    usagesList.add(usage);
                }
                else if (!isInMovedElement(ref)) {
                    usagesList.add(new MoveMembersUsageInfo(member, ref, null, ref, psiReference));
                }
            }
        }
        UsageInfo[] usageInfos = usagesList.toArray(new UsageInfo[usagesList.size()]);
        usageInfos = UsageViewUtil.removeDuplicatedUsages(usageInfos);
        return usageInfos;
    }

    protected void refreshElements(@Nonnull PsiElement[] elements) {
        LOG.assertTrue(myMembersToMove.size() == elements.length);
        myMembersToMove.clear();
        for (PsiElement resolved : elements) {
            myMembersToMove.add((PsiMember)resolved);
        }
    }

    private boolean isInMovedElement(PsiElement element) {
        for (PsiMember member : myMembersToMove) {
            if (PsiTreeUtil.isAncestor(member, element, false)) {
                return true;
            }
        }
        return false;
    }

    protected void performRefactoring(@Nonnull final UsageInfo[] usages) {
        try {
            PsiClass targetClass =
                JavaPsiFacade.getInstance(myProject).findClass(myOptions.getTargetClassName(), GlobalSearchScope.projectScope(myProject));
            if (targetClass == null) {
                return;
            }

            // collect anchors to place moved members at
            final Map<PsiMember, SmartPsiElementPointer<PsiElement>> anchors = new HashMap<PsiMember, SmartPsiElementPointer<PsiElement>>();
            final Map<PsiMember, PsiMember> anchorsInSourceClass = new HashMap<PsiMember, PsiMember>();
            for (PsiMember member : myMembersToMove) {
                final MoveMemberHandler handler = MoveMemberHandler.forLanguage(member.getLanguage());
                if (handler != null) {
                    final PsiElement anchor = handler.getAnchor(member, targetClass, myMembersToMove);
                    if (anchor instanceof PsiMember && myMembersToMove.contains((PsiMember)anchor)) {
                        anchorsInSourceClass.put(member, (PsiMember)anchor);
                    }
                    else {
                        anchors.put(
                            member,
                            anchor == null ? null : SmartPointerManager.getInstance(myProject).createSmartPsiElementPointer(anchor)
                        );
                    }
                }
            }

            // correct references to moved members from the outside
            ArrayList<MoveMembersUsageInfo> otherUsages = new ArrayList<MoveMembersUsageInfo>();
            for (UsageInfo usageInfo : usages) {
                MoveMembersUsageInfo usage = (MoveMembersUsageInfo)usageInfo;
                if (!usage.reference.isValid()) {
                    continue;
                }
                final MoveMemberHandler handler = MoveMemberHandler.forLanguage(usageInfo.getElement().getLanguage());
                if (handler != null) {
                    if (handler.changeExternalUsage(myOptions, usage)) {
                        continue;
                    }
                }
                otherUsages.add(usage);
            }

            // correct references inside moved members and outer references to Inner Classes
            final Map<PsiMember, PsiMember> movedMembers = new HashMap<PsiMember, PsiMember>();
            for (PsiMember member : myMembersToMove) {
                ArrayList<PsiReference> refsToBeRebind = new ArrayList<PsiReference>();
                for (Iterator<MoveMembersUsageInfo> iterator = otherUsages.iterator(); iterator.hasNext(); ) {
                    MoveMembersUsageInfo info = iterator.next();
                    if (member.equals(info.member)) {
                        PsiReference ref = info.getReference();
                        if (ref != null) {
                            refsToBeRebind.add(ref);
                        }
                        iterator.remove();
                    }
                }
                final RefactoringElementListener elementListener = getTransaction().getElementListener(member);
                final MoveMemberHandler handler = MoveMemberHandler.forLanguage(member.getLanguage());
                if (handler != null) {

                    final PsiElement anchor;
                    if (anchorsInSourceClass.containsKey(member)) {
                        final PsiMember memberInSourceClass = anchorsInSourceClass.get(member);
                        //anchor should be already moved as myMembersToMove contains members in order they appear in source class
                        anchor = memberInSourceClass != null ? movedMembers.get(memberInSourceClass) : null;
                    }
                    else {
                        final SmartPsiElementPointer<PsiElement> pointer = anchors.get(member);
                        anchor = pointer != null ? pointer.getElement() : null;
                    }

                    PsiMember newMember = handler.doMove(myOptions, member, anchor, targetClass);

                    movedMembers.put(member, newMember);
                    elementListener.elementMoved(newMember);

                    fixModifierList(member, newMember, usages);
                    for (PsiReference reference : refsToBeRebind) {
                        reference.bindToElement(newMember);
                    }
                }
            }

            // qualifier info must be decoded after members are moved
            final MoveMemberHandler handler = MoveMemberHandler.forLanguage(myTargetClass.getLanguage());
            if (handler != null) {
                handler.decodeContextInfo(myTargetClass);
            }

            myMembersToMove.clear();
            if (myMoveCallback != null) {
                myMoveCallback.refactoringCompleted();
            }

            if (myOpenInEditor && !movedMembers.isEmpty()) {
                final PsiMember item = ContainerUtil.getFirstItem(movedMembers.values());
                if (item != null) {
                    EditorHelper.openInEditor(item);
                }
            }
        }
        catch (IncorrectOperationException e) {
            LOG.error(e);
        }
    }

    private void fixModifierList(PsiMember member, PsiMember newMember, final UsageInfo[] usages) throws IncorrectOperationException {
        PsiModifierList modifierList = newMember.getModifierList();

        if (modifierList != null && myTargetClass.isInterface()) {
            modifierList.setModifierProperty(PsiModifier.PUBLIC, false);
            modifierList.setModifierProperty(PsiModifier.PROTECTED, false);
            modifierList.setModifierProperty(PsiModifier.PRIVATE, false);
            if (newMember instanceof PsiClass) {
                modifierList.setModifierProperty(PsiModifier.STATIC, false);
            }
            return;
        }

        if (myNewVisibility == null) {
            return;
        }

        final List<UsageInfo> filtered = new ArrayList<UsageInfo>();
        for (UsageInfo usage : usages) {
            if (usage instanceof MoveMembersUsageInfo && member == ((MoveMembersUsageInfo)usage).member) {
                filtered.add(usage);
            }
        }
        UsageInfo[] infos = filtered.toArray(new UsageInfo[filtered.size()]);
        VisibilityUtil.fixVisibility(UsageViewUtil.toElements(infos), newMember, myNewVisibility);
    }

    @Override
    @RequiredUIAccess
    protected boolean preprocessUsages(@Nonnull SimpleReference<UsageInfo[]> refUsages) {
        final MultiMap<PsiElement, String> conflicts = new MultiMap<PsiElement, String>();
        final UsageInfo[] usages = refUsages.get();

        String newVisibility = myNewVisibility;
        if (VisibilityUtil.ESCALATE_VISIBILITY.equals(newVisibility)) { // still need to check for access object
            newVisibility = PsiModifier.PUBLIC;
        }

        final Map<PsiMember, PsiModifierList> modifierListCopies = new HashMap<PsiMember, PsiModifierList>();
        for (PsiMember member : myMembersToMove) {
            PsiModifierList modifierListCopy = member.getModifierList();
            if (modifierListCopy != null) {
                modifierListCopy = (PsiModifierList)modifierListCopy.copy();
            }
            if (modifierListCopy != null && newVisibility != null) {
                try {
                    VisibilityUtil.setVisibility(modifierListCopy, newVisibility);
                }
                catch (IncorrectOperationException e) {
                    LOG.error(e);
                }
            }
            modifierListCopies.put(member, modifierListCopy);
        }

        analyzeConflictsOnUsages(usages, myMembersToMove, newVisibility, myTargetClass, modifierListCopies, conflicts);
        analyzeConflictsOnMembers(myMembersToMove, newVisibility, myTargetClass, modifierListCopies, conflicts);

        RefactoringConflictsUtil.analyzeModuleConflicts(myProject, myMembersToMove, usages, myTargetClass, conflicts);

        return showConflicts(conflicts, usages);
    }

    private static void analyzeConflictsOnUsages(
        UsageInfo[] usages,
        Set<PsiMember> membersToMove,
        String newVisibility,
        @Nonnull PsiClass targetClass,
        Map<PsiMember, PsiModifierList> modifierListCopies,
        MultiMap<PsiElement, String> conflicts
    ) {
        for (UsageInfo usage : usages) {
            if (!(usage instanceof MoveMembersUsageInfo)) {
                continue;
            }
            final MoveMembersUsageInfo usageInfo = (MoveMembersUsageInfo)usage;
            final PsiMember member = usageInfo.member;
            final MoveMemberHandler handler = MoveMemberHandler.forLanguage(member.getLanguage());
            if (handler != null) {
                handler.checkConflictsOnUsage(
                    usageInfo,
                    newVisibility,
                    modifierListCopies.get(member),
                    targetClass,
                    membersToMove,
                    conflicts
                );
            }
        }
    }

    private static void analyzeConflictsOnMembers(
        Set<PsiMember> membersToMove,
        String newVisibility,
        PsiClass targetClass,
        Map<PsiMember, PsiModifierList> modifierListCopies,
        MultiMap<PsiElement, String> conflicts
    ) {
        for (final PsiMember member : membersToMove) {
            final MoveMemberHandler handler = MoveMemberHandler.forLanguage(member.getLanguage());
            if (handler != null) {
                handler.checkConflictsOnMember(
                    member,
                    newVisibility,
                    modifierListCopies.get(member),
                    targetClass,
                    membersToMove,
                    conflicts
                );
            }
        }
    }

    @RequiredUIAccess
    public void doRun() {
        if (myMembersToMove.isEmpty()) {
            LocalizeValue message = RefactoringLocalize.noMembersSelected();
            CommonRefactoringUtil.showErrorMessage(MoveMembersImpl.REFACTORING_NAME, message.get(), HelpID.MOVE_MEMBERS, myProject);
            return;
        }
        super.doRun();
    }

    public List<PsiElement> getMembers() {
        return new ArrayList<PsiElement>(myMembersToMove);
    }

    public PsiClass getTargetClass() {
        return myTargetClass;
    }


    public static class MoveMembersUsageInfo extends MoveRenameUsageInfo {
        public final PsiClass qualifierClass;
        public final PsiElement reference;
        public final PsiMember member;

        public MoveMembersUsageInfo(
            PsiMember member,
            PsiElement element,
            PsiClass qualifierClass,
            PsiElement highlightElement,
            final PsiReference ref
        ) {
            super(highlightElement, ref, member);
            this.member = member;
            this.qualifierClass = qualifierClass;
            reference = element;
        }
    }
}
