/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.mentions.hook.service.impl;

import com.liferay.mentions.util.MentionsConstants;
import com.liferay.mentions.util.MentionsUserFinderUtil;
import com.liferay.mentions.util.MentionsUtil;
import com.liferay.mentions.util.PortletKeys;
import com.liferay.mentions.util.PortletPropsValues;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.util.ArrayUtil;
import com.liferay.portal.kernel.util.PrefsPropsUtil;
import com.liferay.portal.kernel.util.PropsKeys;
import com.liferay.portal.kernel.util.StringPool;
import com.liferay.portal.kernel.workflow.WorkflowConstants;
import com.liferay.portal.model.User;
import com.liferay.portal.service.ServiceContext;
import com.liferay.portal.service.UserLocalServiceUtil;
import com.liferay.portal.util.PortalUtil;
import com.liferay.portal.util.SubscriptionSender;
import com.liferay.portlet.messageboards.model.MBMessage;
import com.liferay.portlet.messageboards.service.MBMessageLocalService;
import com.liferay.portlet.messageboards.service.MBMessageLocalServiceUtil;
import com.liferay.portlet.messageboards.service.MBMessageLocalServiceWrapper;
import com.liferay.portlet.social.util.SocialInteractionsConfiguration;
import com.liferay.portlet.social.util.SocialInteractionsConfigurationUtil;
import com.liferay.util.ContentUtil;

import java.io.Serializable;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Sergio González
 * @author Iván Zaera
 */
public class MentionsMessageServiceImpl extends MBMessageLocalServiceWrapper {

	public MentionsMessageServiceImpl(
		MBMessageLocalService mbMessageLocalService) {

		super(mbMessageLocalService);
	}

	@Override
	public MBMessage updateStatus(
			long userId, long messageId, int status,
			ServiceContext serviceContext,
			Map<String, Serializable> workflowContext)
		throws PortalException {

		MBMessage message = MBMessageLocalServiceUtil.getMessage(messageId);

		int oldStatus = message.getStatus();

		message = super.updateStatus(
			userId, messageId, status, serviceContext, workflowContext);

		if (!message.isDiscussion() ||
			(status != WorkflowConstants.STATUS_APPROVED) ||
			(oldStatus == WorkflowConstants.STATUS_IN_TRASH) ||
			(oldStatus == WorkflowConstants.STATUS_APPROVED)) {

			return message;
		}

		long siteGroupId = PortalUtil.getSiteGroupId(message.getGroupId());

		if (!MentionsUtil.isMentionsEnabled(siteGroupId)) {
			return message;
		}

		notifyUsers(message, serviceContext);

		return message;
	}

	protected String[] getMentionedUsersScreenNames(MBMessage message)
		throws PortalException {

		SocialInteractionsConfiguration socialInteractionsConfiguration =
			SocialInteractionsConfigurationUtil.
				getSocialInteractionsConfiguration(
					message.getCompanyId(), PortletKeys.MENTIONS);

		Matcher matcher = _pattern.matcher(message.getBody());

		Set<String> mentionedUsersScreenNames = new HashSet<String>();

		while (matcher.find()) {
			String mentionedUserScreenName = matcher.group(1);

			List<User> users = MentionsUserFinderUtil.getUsers(
				message.getCompanyId(), message.getUserId(),
				mentionedUserScreenName, socialInteractionsConfiguration);

			for (User user : users) {
				if (mentionedUserScreenName.equals(user.getScreenName())) {
					mentionedUsersScreenNames.add(mentionedUserScreenName);

					break;
				}
			}
		}

		return mentionedUsersScreenNames.toArray(
			new String[mentionedUsersScreenNames.size()]);
	}

	protected void notifyUsers(MBMessage message, ServiceContext serviceContext)
		throws PortalException {

		if (!message.isDiscussion()) {
			return;
		}

		String[] mentionedUsersScreenNames = getMentionedUsersScreenNames(
			message);

		if (ArrayUtil.isEmpty(mentionedUsersScreenNames)) {
			return;
		}

		String contentURL = (String)serviceContext.getAttribute("contentURL");

		String messageUserEmailAddress = PortalUtil.getUserEmailAddress(
			message.getUserId());
		String messageUserName = PortalUtil.getUserName(
			message.getUserId(), StringPool.BLANK);

		String fromName = PrefsPropsUtil.getString(
			message.getCompanyId(), PropsKeys.ADMIN_EMAIL_FROM_NAME);
		String fromAddress = PrefsPropsUtil.getString(
			message.getCompanyId(), PropsKeys.ADMIN_EMAIL_FROM_ADDRESS);

		String subject = ContentUtil.get(
			PortletPropsValues.MB_DISCUSSION_EMAIL_SUBJECT);
		String body = ContentUtil.get(
			PortletPropsValues.MB_DISCUSSION_EMAIL_BODY);

		SubscriptionSender subscriptionSender = new SubscriptionSender();

		subscriptionSender.setBody(body);
		subscriptionSender.setClassName(message.getModelClassName());
		subscriptionSender.setClassPK(message.getMessageId());
		subscriptionSender.setCompanyId(message.getCompanyId());
		subscriptionSender.setContextAttribute(
			"[$COMMENTS_BODY$]", message.getBody(true), false);
		subscriptionSender.setContextAttributes(
			"[$COMMENTS_USER_ADDRESS$]", messageUserEmailAddress,
			"[$COMMENTS_USER_NAME$]", messageUserName, "[$CONTENT_URL$]",
			contentURL);
		subscriptionSender.setEntryTitle(message.getBody());
		subscriptionSender.setEntryURL(contentURL);
		subscriptionSender.setFrom(fromAddress, fromName);
		subscriptionSender.setHtmlFormat(true);
		subscriptionSender.setMailId(
			"mb_discussion", message.getCategoryId(), message.getMessageId());
		subscriptionSender.setNotificationType(
			MentionsConstants.NOTIFICATION_TYPE_MENTION);
		subscriptionSender.setPortletId(PortletKeys.MENTIONS);
		subscriptionSender.setScopeGroupId(message.getGroupId());
		subscriptionSender.setServiceContext(serviceContext);
		subscriptionSender.setSubject(subject);
		subscriptionSender.setUserId(message.getUserId());

		for (int i = 0; i < mentionedUsersScreenNames.length; i++) {
			String mentionedUserScreenName = mentionedUsersScreenNames[i];

			User user = UserLocalServiceUtil.fetchUserByScreenName(
				message.getCompanyId(), mentionedUserScreenName);

			if (user == null) {
				continue;
			}

			subscriptionSender.addRuntimeSubscribers(
				user.getEmailAddress(), user.getFullName());
		}

		subscriptionSender.flushNotificationsAsync();
	}

	private static Pattern _pattern = Pattern.compile(
		"(?:\\s|^)@([^@\\s]+)", Pattern.CASE_INSENSITIVE);

}