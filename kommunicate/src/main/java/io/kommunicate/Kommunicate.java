package io.kommunicate;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.text.TextUtils;


import com.applozic.mobicomkit.Applozic;
import com.applozic.mobicomkit.ApplozicClient;
import com.applozic.mobicomkit.api.MobiComKitClientService;
import com.applozic.mobicomkit.api.account.register.RegistrationResponse;
import com.applozic.mobicomkit.api.account.user.MobiComUserPreference;
import com.applozic.mobicomkit.api.account.user.PushNotificationTask;
import com.applozic.mobicomkit.api.account.user.User;
import com.applozic.mobicomkit.api.notification.MobiComPushReceiver;
import com.applozic.mobicomkit.api.people.ChannelInfo;
import com.applozic.mobicomkit.database.MobiComDatabaseHelper;
import com.applozic.mobicomkit.feed.ChannelFeedApiResponse;

import io.kommunicate.activities.LeadCollectionActivity;

import com.applozic.mobicomkit.uiwidgets.async.AlChannelCreateAsyncTask;
import com.applozic.mobicomkit.uiwidgets.async.AlGroupInformationAsyncTask;
import com.applozic.mobicomkit.uiwidgets.conversation.ConversationUIService;
import com.applozic.mobicomkit.uiwidgets.conversation.activity.ConversationActivity;
import com.applozic.mobicommons.ApplozicService;
import com.applozic.mobicommons.commons.core.utils.Utils;
import com.applozic.mobicommons.json.GsonUtils;
import com.applozic.mobicommons.people.channel.Channel;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import io.kommunicate.async.GetUserListAsyncTask;
import io.kommunicate.async.KMFaqTask;
import io.kommunicate.async.KMHelpDocsKeyTask;
import io.kommunicate.async.KmGetAgentListTask;
import io.kommunicate.async.KmUserLoginTask;
import io.kommunicate.callbacks.KMStartChatHandler;
import io.kommunicate.callbacks.KMGetContactsHandler;
import io.kommunicate.callbacks.KMLogoutHandler;
import io.kommunicate.callbacks.KMLoginHandler;
import io.kommunicate.callbacks.KmCallback;
import io.kommunicate.callbacks.KmFaqTaskListener;
import io.kommunicate.callbacks.KmPrechatCallback;
import io.kommunicate.callbacks.KmPushNotificationHandler;
import io.kommunicate.models.KmAgentModel;
import io.kommunicate.users.KMUser;

/**
 * Created by ashish on 23/01/18.
 */

public class Kommunicate {

    private static final String KM_BOT = "bot";
    public static final String START_NEW_CHAT = "startNewChat";
    public static final String LOGOUT_CALL = "logoutCall";
    public static final String PRECHAT_LOGIN_CALL = "prechatLogin";
    private static final String TAG = "KommunicateTag";
    private static final String CONVERSATION_ASSIGNEE = "CONVERSATION_ASSIGNEE";
    private static final String SKIP_ROUTING = "SKIP_ROUTING";

    public static void init(Context context, String applicationKey) {
        Applozic.init(context, applicationKey);
    }

    public static void login(Context context, KMUser kmUser, KMLoginHandler handler) {
        new KmUserLoginTask(kmUser, false, handler, context).execute();
    }

    public static void login(Context context, KMUser kmUser, KMLoginHandler handler, ResultReceiver prechatReceiver) {
        new KmUserLoginTask(kmUser, false, handler, context, prechatReceiver).execute();
    }

    public static void loginAsVisitor(Context context, KMLoginHandler handler) {
        login(context, getVisitor(), handler);
    }

    public static void logout(Context context, final KMLogoutHandler logoutHandler) {
        KMLogoutHandler handler = new KMLogoutHandler() {
            @Override
            public void onSuccess(Context context) {
                ApplozicService.getContext(context).deleteDatabase(MobiComDatabaseHelper.getInstance(context).getDatabaseName());
                logoutHandler.onSuccess(context);
            }

            @Override
            public void onFailure(Exception exception) {
                logoutHandler.onFailure(exception);
            }
        };

        Applozic.logoutUser(context, handler);
    }

    public static void setDeviceToken(Context context, String deviceToken) {
        Applozic.getInstance(context).setDeviceRegistrationId(deviceToken);
    }

    public static String getDeviceToken(Context context) {
        return Applozic.getInstance(context).getDeviceRegistrationId();
    }

    public static void openConversation(Context context) {
        openConversation(context, null, null);
    }

    public static void openConversation(Context context, Integer conversationId, KmCallback callback) {
        try {
            KmConversationHelper.openConversation(context, true, conversationId, callback);
        } catch (KmException e) {
            e.printStackTrace();
            if (callback != null) {
                callback.onFailure(e.getMessage());
            }
        }
    }

    public static void openConversation(Context context, KmCallback callback) {
        Intent intent = new Intent(context, ConversationActivity.class);
        context.startActivity(intent);
        if (callback != null) {
            callback.onSuccess("Successfully launched chat list");
        }
    }

    @Deprecated
    public static void openConversation(Context context, boolean prechatLeadCollection) {
        Intent intent = new Intent(context, (prechatLeadCollection && !KMUser.isLoggedIn(context)) ? LeadCollectionActivity.class : ConversationActivity.class);
        context.startActivity(intent);
    }

    public static void launchPrechatWithResult(Context context, final KmPrechatCallback callback) throws KmException {
        if (!(context instanceof Activity)) {
            throw new KmException("This method needs Activity context");
        }

        ResultReceiver resultReceiver = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                if (LeadCollectionActivity.PRECHAT_RESULT_CODE == resultCode) {
                    KMUser user = (KMUser) GsonUtils.getObjectFromJson(resultData.getString(LeadCollectionActivity.KM_USER_DATA), KMUser.class);
                    if (callback != null) {
                        callback.onReceive(user, (ResultReceiver) resultData.getParcelable(LeadCollectionActivity.FINISH_ACTIVITY_RECEIVER));
                    }
                }
            }
        };

        Intent intent = new Intent(context, LeadCollectionActivity.class);
        intent.putExtra(LeadCollectionActivity.PRECHAT_RESULT_RECEIVER, resultReceiver);
        context.startActivity(intent);
    }

    @Deprecated
    public static void launchSingleChat(final Context context, final String groupName, KMUser kmUser, boolean withPreChat, final boolean isUnique, final List<String> agents, final List<String> bots, final String clientConversationId, final KmCallback callback) {
        if (callback == null) {
            return;
        }
        if (!(context instanceof Activity)) {
            callback.onFailure("This method needs Activity context");
        }

        final KMStartChatHandler startChatHandler = new KMStartChatHandler() {
            @Override
            public void onSuccess(Channel channel, Context context) {
                callback.onSuccess(channel);
                openParticularConversation(context, channel.getKey());
            }

            @Override
            public void onFailure(ChannelFeedApiResponse channelFeedApiResponse, Context context) {
                callback.onFailure(channelFeedApiResponse);
            }
        };

        final KmChatBuilder chatBuilder = new KmChatBuilder(context);
        chatBuilder.setChatName(groupName)
                .setKmUser(kmUser)
                .setWithPreChat(withPreChat)
                .setSingleChat(isUnique)
                .setAgentIds(agents)
                .setBotIds(bots)
                .setClientConversationId(clientConversationId);

        if (isLoggedIn(context)) {
            try {
                startConversation(chatBuilder, startChatHandler);
            } catch (KmException e) {
                callback.onFailure(e);
            }
        } else {
            final KMLoginHandler loginHandler = new KMLoginHandler() {
                @Override
                public void onSuccess(RegistrationResponse registrationResponse, Context context) {
                    try {
                        startConversation(chatBuilder, startChatHandler);
                    } catch (KmException e) {
                        e.printStackTrace();
                        callback.onFailure(e);
                    }
                }

                @Override
                public void onFailure(RegistrationResponse registrationResponse, Exception exception) {
                    callback.onFailure(registrationResponse);
                }
            };

            if (withPreChat) {
                try {
                    launchPrechatWithResult(context, new KmPrechatCallback() {
                        @Override
                        public void onReceive(KMUser user, ResultReceiver finishActivityReceiver) {
                            login(context, user, loginHandler, finishActivityReceiver);
                        }
                    });
                } catch (KmException e) {
                    callback.onFailure(e);
                }
            } else {
                login(context, kmUser, loginHandler);
            }
        }
    }

    public static void setNotificationSoundPath(Context context, String path) {
        Applozic.getInstance(context).setCustomNotificationSound(path);
    }

    @Deprecated
    public static void openParticularConversation(Context context, Integer groupId) {
        Intent intent = new Intent(context, ConversationActivity.class);
        intent.putExtra(ConversationUIService.GROUP_ID, groupId);
        intent.putExtra(ConversationUIService.TAKE_ORDER, true); //Skip chat list for showing on back press
        context.startActivity(intent);
    }

    @Deprecated
    public static void startConversation(final KmChatBuilder chatBuilder, final KMStartChatHandler handler) throws KmException {
        if (chatBuilder == null) {
            throw new KmException("KmChatBuilder cannot be null");
        }

        if (chatBuilder.getAgentIds() == null || chatBuilder.getAgentIds().isEmpty()) {
            KmCallback callback = new KmCallback() {
                @Override
                public void onSuccess(Object message) {
                    KmAgentModel.KmResponse agent = (KmAgentModel.KmResponse) message;
                    if (agent != null) {
                        List<String> agents = new ArrayList<>();
                        agents.add(agent.getAgentId());
                        chatBuilder.setAgentIds(agents);
                        try {
                            final String clientChannelKey = !TextUtils.isEmpty(chatBuilder.getClientConversationId()) ? chatBuilder.getClientConversationId() : (chatBuilder.isSingleChat() ? getClientGroupId(MobiComUserPreference.getInstance(chatBuilder.getContext()).getUserId(), agents, chatBuilder.getBotIds()) : null);
                            if (!TextUtils.isEmpty(clientChannelKey)) {
                                chatBuilder.setClientConversationId(clientChannelKey);
                                startOrGetConversation(chatBuilder, handler);
                            } else {
                                createConversation(chatBuilder, handler);
                            }
                        } catch (KmException e) {
                            e.printStackTrace();
                        }
                    }
                }

                @Override
                public void onFailure(Object error) {
                    if (handler != null) {
                        handler.onFailure(null, chatBuilder.getContext());
                    }
                }
            };

            new KmGetAgentListTask(chatBuilder.getContext(), MobiComKitClientService.getApplicationKey(chatBuilder.getContext()), callback).execute();
        } else {
            final String clientChannelKey = !TextUtils.isEmpty(chatBuilder.getClientConversationId()) ? chatBuilder.getClientConversationId() : (chatBuilder.isSingleChat() ? getClientGroupId(MobiComUserPreference.getInstance(chatBuilder.getContext()).getUserId(), chatBuilder.getAgentIds(), chatBuilder.getBotIds()) : null);
            if (!TextUtils.isEmpty(clientChannelKey)) {
                startOrGetConversation(chatBuilder, handler);
            } else {
                createConversation(chatBuilder, handler);
            }
        }
    }

    @Deprecated
    private static void createConversation(KmChatBuilder chatBuilder, KMStartChatHandler handler) throws KmException {
        List<KMGroupInfo.GroupUser> users = new ArrayList<>();

        KMGroupInfo channelInfo = new KMGroupInfo(TextUtils.isEmpty(chatBuilder.getChatName()) ? "Kommunicate Support" : chatBuilder.getChatName(), new ArrayList<String>());

        if (chatBuilder.getAgentIds() == null || chatBuilder.getAgentIds().isEmpty()) {
            throw new KmException("Agent Id list cannot be null or empty");
        }
        for (String agentId : chatBuilder.getAgentIds()) {
            users.add(channelInfo.new GroupUser().setUserId(agentId).setGroupRole(1));
        }

        users.add(channelInfo.new GroupUser().setUserId(KM_BOT).setGroupRole(2));
        users.add(channelInfo.new GroupUser().setUserId(MobiComUserPreference.getInstance(chatBuilder.getContext()).getUserId()).setGroupRole(3));

        if (chatBuilder.getBotIds() != null) {
            for (String botId : chatBuilder.getBotIds()) {
                if (botId != null && !KM_BOT.equals(botId)) {
                    users.add(channelInfo.new GroupUser().setUserId(botId).setGroupRole(2));
                }
            }
        }

        channelInfo.setType(10);
        channelInfo.setUsers(users);

        if (!chatBuilder.getAgentIds().isEmpty()) {
            channelInfo.setAdmin(chatBuilder.getAgentIds().get(0));
        }

        if (!TextUtils.isEmpty(chatBuilder.getClientConversationId())) {
            channelInfo.setClientGroupId(chatBuilder.getClientConversationId());
        } else if (chatBuilder.isSingleChat()) {
            channelInfo.setClientGroupId(getClientGroupId(MobiComUserPreference.getInstance(chatBuilder.getContext()).getUserId(), chatBuilder.getAgentIds(), chatBuilder.getBotIds()));
        }

        Map<String, String> metadata = new HashMap<>();
        metadata.put("CREATE_GROUP_MESSAGE", "");
        metadata.put("REMOVE_MEMBER_MESSAGE", "");
        metadata.put("ADD_MEMBER_MESSAGE", "");
        metadata.put("JOIN_MEMBER_MESSAGE", "");
        metadata.put("GROUP_NAME_CHANGE_MESSAGE", "");
        metadata.put("GROUP_ICON_CHANGE_MESSAGE", "");
        metadata.put("GROUP_LEFT_MESSAGE", "");
        metadata.put("DELETED_GROUP_MESSAGE", "");
        metadata.put("GROUP_USER_ROLE_UPDATED_MESSAGE", "");
        metadata.put("GROUP_META_DATA_UPDATED_MESSAGE", "");
        metadata.put("HIDE", "true");

        if (!TextUtils.isEmpty(chatBuilder.getConversationAssignee())) {
            metadata.put(CONVERSATION_ASSIGNEE, chatBuilder.getConversationAssignee());
            metadata.put(SKIP_ROUTING, "true");
        }

        if (chatBuilder.isSkipRouting()) {
            metadata.put(SKIP_ROUTING, String.valueOf(chatBuilder.isSkipRouting()));
        }

        if (!TextUtils.isEmpty(ApplozicClient.getInstance(chatBuilder.getContext()).getMessageMetaData())) {
            Map<String, String> defaultMetadata = (Map<String, String>) GsonUtils.getObjectFromJson(ApplozicClient.getInstance(chatBuilder.getContext()).getMessageMetaData(), Map.class);
            if (defaultMetadata != null) {
                metadata.putAll(defaultMetadata);
            }
        }

        channelInfo.setMetadata(metadata);

        Utils.printLog(chatBuilder.getContext(), TAG, "ChannelInfo : " + GsonUtils.getJsonFromObject(channelInfo, ChannelInfo.class));

        if (handler == null) {
            handler = new KMStartChatHandler() {
                @Override
                public void onSuccess(Channel channel, Context context) {

                }

                @Override
                public void onFailure(ChannelFeedApiResponse channelFeedApiResponse, Context context) {

                }
            };
        }

        new AlChannelCreateAsyncTask(chatBuilder.getContext(), channelInfo, handler).execute();
    }

    public static void getAgents(Context context, int startIndex, int pageSize, KMGetContactsHandler handler) {
        List<String> roleName = new ArrayList<>();
        roleName.add(KMUser.RoleName.APPLICATION_ADMIN.getValue());
        roleName.add(KMUser.RoleName.APPLICATION_WEB_ADMIN.getValue());

        new GetUserListAsyncTask(context, roleName, startIndex, pageSize, handler).execute();
    }

    public static void getFaqs(Context context, String type, String helpDocsKey, String data, KmFaqTaskListener listener) {
        KMFaqTask task = new KMFaqTask(context, helpDocsKey, data, listener);
        if ("getArticles".equals(type)) {
            task.forArticleRequest();
        } else if ("getSelectedArticles".equals(type)) {
            task.forSelectedArticles();
        } else if ("getAnswers".equals(type)) {
            task.forAnswerRequest();
        } else if ("getDashboardFaq".equals(type)) {
            task.forDashboardFaq();
        }
        task.execute();
    }

    public static void getHelpDocsKey(Context context, String type, KmFaqTaskListener listener) {
        new KMHelpDocsKeyTask(context, type, listener).execute();
    }

    public static boolean isLoggedIn(Context context) {
        return MobiComUserPreference.getInstance(context).isLoggedIn();
    }

    public static void registerForPushNotification(Context context, String token, KmPushNotificationHandler listener) {
        new PushNotificationTask(context, token, listener).execute();
    }

    public static void registerForPushNotification(Context context, KmPushNotificationHandler listener) {
        registerForPushNotification(context, Kommunicate.getDeviceToken(context), listener);
    }

    public static boolean isKmNotification(Context context, Map<String, String> data) {
        if (MobiComPushReceiver.isMobiComPushNotification(data)) {
            MobiComPushReceiver.processMessageAsync(context, data);
            return true;
        }
        return false;
    }

    @Deprecated
    private static void startOrGetConversation(final KmChatBuilder chatBuilder, final KMStartChatHandler handler) throws KmException {

        AlGroupInformationAsyncTask.GroupMemberListener groupMemberListener = new AlGroupInformationAsyncTask.GroupMemberListener() {
            @Override
            public void onSuccess(Channel channel, Context context) {
                if (handler != null) {
                    handler.onSuccess(channel, context);
                }
            }

            @Override
            public void onFailure(Channel channel, Exception e, Context context) {
                try {
                    createConversation(chatBuilder, handler);
                } catch (KmException e1) {
                    handler.onFailure(null, context);
                }
            }
        };

        new AlGroupInformationAsyncTask(chatBuilder.getContext(), chatBuilder.getClientConversationId(), groupMemberListener).execute();
    }

    private static String getClientGroupId(String userId, List<String> agentIds, List<String> botIds) throws KmException {

        if (agentIds == null || agentIds.isEmpty()) {
            throw new KmException("Please add at-least one Agent");
        }

        if (TextUtils.isEmpty(userId)) {
            throw new KmException("UserId cannot be null");
        }

        Collections.sort(agentIds);

        List<String> tempList = new ArrayList<>(agentIds);
        tempList.add(userId);

        if (botIds != null && !botIds.isEmpty()) {
            if (botIds.contains(KM_BOT)) {
                botIds.remove(KM_BOT);
            }
            Collections.sort(botIds);
            tempList.addAll(botIds);
        }

        StringBuilder sb = new StringBuilder();

        Iterator<String> iterator = tempList.iterator();

        while (iterator.hasNext()) {
            String temp = iterator.next();
            if (temp == null) {
                continue;
            }
            sb.append(temp);

            if (!temp.equals(tempList.get(tempList.size() - 1))) {
                sb.append("_");
            }
        }

        if (sb.toString().length() > 255) {
            throw new KmException("Please reduce the number of agents or bots");
        }

        return sb.toString();
    }

    public static KMUser getVisitor() {
        KMUser user = new KMUser();
        user.setUserId(generateUserId());
        user.setAuthenticationTypeId(User.AuthenticationType.APPLOZIC.getValue());
        return user;
    }

    private static String generateUserId() {
        StringBuilder text = new StringBuilder("");
        SecureRandom random = new SecureRandom();
        String possible = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

        for (int i = 0; i < 32; i++) {
            text.append(possible.charAt(random.nextInt(possible.length())));
        }
        return text.toString();
    }
}
