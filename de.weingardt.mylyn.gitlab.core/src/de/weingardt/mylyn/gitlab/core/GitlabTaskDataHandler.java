/*******************************************************************************
 * Copyright (c) 2014, Paul Weingardt All rights reserved. This program and the accompanying
 * materials are made available under the terms of the Eclipse Public License v1.0 which accompanies
 * this distribution, and is available at http://www.eclipse.org/legal/epl-v10.html Contributors:
 * Paul Weingardt - initial API and implementation
 *******************************************************************************/

package de.weingardt.mylyn.gitlab.core;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.mylyn.tasks.core.ITask.PriorityLevel;
import org.eclipse.mylyn.tasks.core.ITaskMapping;
import org.eclipse.mylyn.tasks.core.RepositoryResponse;
import org.eclipse.mylyn.tasks.core.RepositoryResponse.ResponseKind;
import org.eclipse.mylyn.tasks.core.TaskRepository;
import org.eclipse.mylyn.tasks.core.data.AbstractTaskDataHandler;
import org.eclipse.mylyn.tasks.core.data.TaskAttribute;
import org.eclipse.mylyn.tasks.core.data.TaskAttributeMapper;
import org.eclipse.mylyn.tasks.core.data.TaskAttributeMetaData;
import org.eclipse.mylyn.tasks.core.data.TaskCommentMapper;
import org.eclipse.mylyn.tasks.core.data.TaskData;
import org.eclipse.mylyn.tasks.core.data.TaskOperation;
import org.gitlab.api.GitlabAPI;
import org.gitlab.api.models.GitlabIssue;
import org.gitlab.api.models.GitlabMilestone;
import org.gitlab.api.models.GitlabNote;
import org.gitlab.api.models.GitlabProjectMember;

import de.weingardt.mylyn.gitlab.core.exceptions.GitlabException;

/**
 * Handles the issues. It maps the attributes from the GitlabAPI to real Mylyn issue attributes,
 * downloads the issues for a specific task repository, and creates new issues.
 *
 * @author paul
 */
public class GitlabTaskDataHandler extends AbstractTaskDataHandler {

  private static Pattern priorityPattern = Pattern.compile("(?:priority:)?(high|normal|low)");

  private static Pattern typePattern = Pattern.compile("(?:type:)?(bug|feature|story)");

  public GitlabTaskDataHandler() {}

  private void createAttribute(TaskData data, GitlabAttribute attribute) {
    final TaskAttribute attr = data.getRoot().createAttribute(attribute.getTaskKey());
    final TaskAttributeMetaData metaData = attr.getMetaData();
    metaData.setType(attribute.getType());
    metaData.setKind(attribute.getKind());
    metaData.setLabel(attribute.toString());
    metaData.setReadOnly(attribute.isReadOnly());
  }

  private void createDefaultAttributes(TaskData data, boolean existingTask) {
    createAttribute(data, GitlabAttribute.BODY);
    createAttribute(data, GitlabAttribute.TITLE);
    createAttribute(data, GitlabAttribute.LABELS);
    createAttribute(data, GitlabAttribute.STATUS);
    createAttribute(data, GitlabAttribute.PROJECT);

    createAttribute(data, GitlabAttribute.CREATED);
    createAttribute(data, GitlabAttribute.COMPLETED);
    createAttribute(data, GitlabAttribute.UPDATED);
    createAttribute(data, GitlabAttribute.ASSIGNEE);
    createAttribute(data, GitlabAttribute.MILESTONE);

    createAttribute(data, GitlabAttribute.IID);
    createAttribute(data, GitlabAttribute.PRIORITY);
    createAttribute(data, GitlabAttribute.TYPE);

    data.getRoot().getAttribute(GitlabAttribute.CREATED.getTaskKey())
        .setValue("" + new Date().getTime());

    if (existingTask) {
      data.getRoot().createAttribute(TaskAttribute.COMMENT_NEW)
          .getMetaData().setType(TaskAttribute.TYPE_LONG_RICH_TEXT)
          .setReadOnly(false);

      createAttribute(data, GitlabAttribute.AUTHOR);
    }

    final TaskAttribute operation = data.getRoot().createAttribute(TaskAttribute.OPERATION);
    operation.getMetaData().setType(TaskAttribute.TYPE_OPERATION);
  }

  public TaskData createTaskDataFromGitlabIssue(GitlabIssue issue, TaskRepository repository,
      List<GitlabNote> notes) throws CoreException {
    final GitlabConnection connection = ConnectionManager.get(repository);
    final TaskData data =
        new TaskData(connection.mapper, GitlabPluginCore.CONNECTOR_KIND, repository.getUrl(),
            "" + issue.getIid());

    final String labels = StringUtils.join(issue.getLabels(), ", ");

    createDefaultAttributes(data, true);

    final TaskAttribute root = data.getRoot();
    root.getAttribute(GitlabAttribute.AUTHOR.getTaskKey())
        .setValue(issue.getAuthor().getName());
    root.getAttribute(GitlabAttribute.CREATED.getTaskKey())
        .setValue("" + issue.getCreatedAt().getTime());
    root.getAttribute(GitlabAttribute.BODY.getTaskKey())
        .setValue(issue.getDescription() == null ? "" : issue.getDescription());
    root.getAttribute(GitlabAttribute.LABELS.getTaskKey()).setValue(labels);
    root.getAttribute(GitlabAttribute.PROJECT.getTaskKey())
        .setValue(connection.project.getName());
    root.getAttribute(GitlabAttribute.STATUS.getTaskKey()).setValue(issue.getState());
    root.getAttribute(GitlabAttribute.TITLE.getTaskKey()).setValue(issue.getTitle());

    root.getAttribute(GitlabAttribute.IID.getTaskKey()).setValue("" + issue.getIid());
    root.getAttribute(GitlabAttribute.PRIORITY.getTaskKey()).setValue(getPriority(labels));
    root.getAttribute(GitlabAttribute.TYPE.getTaskKey()).setValue(getType(labels));

    if (issue.getMilestone() != null) {
      root.getAttribute(GitlabAttribute.MILESTONE.getTaskKey())
          .setValue(issue.getMilestone().getTitle());
    }

    if (issue.getUpdatedAt() != null) {
      root.getAttribute(GitlabAttribute.UPDATED.getTaskKey())
          .setValue("" + issue.getUpdatedAt().getTime());
    }

    if (issue.getState().equals(GitlabIssue.STATE_CLOSED)) {
      root.getAttribute(GitlabAttribute.COMPLETED.getTaskKey())
          .setValue("" + issue.getUpdatedAt().getTime());
    }

    if (issue.getAssignee() != null) {
      root.getAttribute(GitlabAttribute.ASSIGNEE.getTaskKey())
          .setValue(issue.getAssignee().getName());
    }

    Collections.sort(notes, new Comparator<GitlabNote>() {
      @Override
      public int compare(GitlabNote o1, GitlabNote o2) {
        return o1.getCreatedAt().compareTo(o2.getCreatedAt());
      }
    });

    for (int i = 0; i < notes.size(); i++) {
      final TaskCommentMapper cmapper = new TaskCommentMapper();
      cmapper.setAuthor(repository.createPerson(notes.get(i).getAuthor().getName()));
      cmapper.setCreationDate(notes.get(i).getCreatedAt());
      cmapper.setText(notes.get(i).getBody());
      cmapper.setNumber(i + 1);
      final TaskAttribute attribute =
          data.getRoot().createAttribute(TaskAttribute.PREFIX_COMMENT + (i + 1));
      cmapper.applyTo(attribute);
    }

    final GitlabAction[] actions = GitlabAction.getActions(issue);
    for (final GitlabAction action : actions) {
      final TaskAttribute attribute =
          data.getRoot().createAttribute(TaskAttribute.PREFIX_OPERATION + action.label);
      TaskOperation.applyTo(attribute, action.label, action.label);
    }

    return data;
  }

  public TaskData downloadTaskData(TaskRepository repository, Integer ticketId)
      throws CoreException {
    try {
      final GitlabConnection connection = ConnectionManager.get(repository);
      final GitlabAPI api = connection.api();
      final GitlabIssue issue = api.getIssue(connection.project.getId(), ticketId);
      final List<GitlabNote> notes = api.getNotes(issue);

      return createTaskDataFromGitlabIssue(issue, repository, notes);
    } catch (final IOException e) {
      throw new GitlabException("Unknown connection error!");
    }
  }

  @Override
  public TaskAttributeMapper getAttributeMapper(TaskRepository repository) {
    try {
      return ConnectionManager.get(repository).mapper;
    } catch (final CoreException e) {
      throw new Error(e);
    }
  }

  /**
   * Returns the Priority String for Mylyn. Uses a regular expression to check for priorities in the
   * given label.
   *
   * @param labels
   * @return
   */
  private String getPriority(String labels) {
    final Matcher m = priorityPattern.matcher(labels);
    if (m.find()) {
      final String p = m.group(1);
      if (p.equals("high")) {
        return PriorityLevel.P1.toString();
      } else if (p.equals("low")) {
        return PriorityLevel.P5.toString();
      }
    }

    return PriorityLevel.P3.toString();
  }

  /**
   * Returns the type string for Mylyn. Uses a regular expression to check for types in the given
   * label.
   *
   * @param labels
   * @return
   */
  private String getType(String labels) {
    final Matcher m = typePattern.matcher(labels);
    if (m.find()) {
      return m.group(1);
    }

    return "";
  }

  @Override
  public boolean initializeTaskData(TaskRepository repository, TaskData data,
      ITaskMapping mapping, IProgressMonitor monitor) throws CoreException {
    createDefaultAttributes(data, false);

    final GitlabConnection connection = ConnectionManager.get(repository);
    final TaskAttribute root = data.getRoot();

    root.getAttribute(GitlabAttribute.PROJECT.getTaskKey())
        .setValue(connection.project.getName());
    root.getAttribute(GitlabAttribute.LABELS.getTaskKey()).setValue("");
    root.getAttribute(GitlabAttribute.STATUS.getTaskKey()).setValue("open");
    root.getAttribute(GitlabAttribute.MILESTONE.getTaskKey()).setValue("");

    return true;
  }

  @Override
  public RepositoryResponse postTaskData(TaskRepository repository, TaskData data,
      Set<TaskAttribute> attributes, IProgressMonitor monitor)
      throws CoreException {

    final GitlabAttributeMapper attributeMapper =
        (GitlabAttributeMapper) data.getAttributeMapper();

    final TaskAttribute root = data.getRoot();
    final String labels = root.getAttribute(GitlabAttribute.LABELS.getTaskKey()).getValue();
    final String title = root.getAttribute(GitlabAttribute.TITLE.getTaskKey()).getValue();
    final String body = root.getAttribute(GitlabAttribute.BODY.getTaskKey()).getValue();

    Integer assigneeId = 0;

    // We have to check, if the assignee has changed. The gitlab api leaves three posiblities for
    // the assignee ID:
    // 0: leave as it is
    // -1: unassign
    // real id: assign
    // If we didnt do this, Gitlab would create a comment everytime we edit the issue and there is
    // still no
    // assignee
    for (final TaskAttribute a : attributes) {
      if (a.getId().equals(GitlabAttribute.ASSIGNEE.getTaskKey())) {
        final GitlabProjectMember assignee = attributeMapper.findProjectMemberByName(
            root.getAttribute(GitlabAttribute.ASSIGNEE.getTaskKey()).getValue());
        assigneeId = assignee == null ? -1 : assignee.getId();
      }
    }

    final GitlabMilestone milestone = attributeMapper.findMilestoneByName(
        root.getAttribute(GitlabAttribute.MILESTONE.getTaskKey()).getValue());
    final Integer milestoneId = milestone == null ? 0 : milestone.getId();

    final GitlabConnection connection = ConnectionManager.get(repository);
    final GitlabAPI api = connection.api();

    try {
      monitor.beginTask("Uploading task", IProgressMonitor.UNKNOWN);
      GitlabIssue issue = null;
      if (data.isNew()) {
        issue = api.createIssue(connection.project.getId(), assigneeId, milestoneId, labels,
            body, title);
        return new RepositoryResponse(ResponseKind.TASK_CREATED, "" + issue.getIid());
      } else {

        if (root.getAttribute(TaskAttribute.COMMENT_NEW) != null &&
            !root.getAttribute(TaskAttribute.COMMENT_NEW).getValue().equals("")) {
          api.createNote(connection.project.getId(),
              GitlabConnector.getTicketId(data.getTaskId()),
              root.getAttribute(TaskAttribute.COMMENT_NEW).getValue());
        }

        final String action = root.getAttribute(TaskAttribute.OPERATION).getValue();

        issue = api.editIssue(connection.project.getId(),
            GitlabConnector.getTicketId(data.getTaskId()), assigneeId,
            milestoneId, labels, body, title, GitlabAction.find(action).getGitlabIssueAction());
        return new RepositoryResponse(ResponseKind.TASK_UPDATED, "" + issue.getIid());
      }
    } catch (final IOException e) {
      throw new GitlabException("Unknown connection error!");
    } finally {
      monitor.done();
    }
  }

}
