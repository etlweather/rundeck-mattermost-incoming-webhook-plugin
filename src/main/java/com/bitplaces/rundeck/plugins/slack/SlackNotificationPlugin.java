/*
 * Copyright 2014 Andrew Karpow
 * based on Slack Plugin from Hayden Bakkum
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.bitplaces.rundeck.plugins.slack;

import com.dtolabs.rundeck.core.plugins.Plugin;
import com.dtolabs.rundeck.plugins.descriptions.PluginDescription;
import com.dtolabs.rundeck.plugins.descriptions.PluginProperty;
import com.dtolabs.rundeck.plugins.notification.NotificationPlugin;
import freemarker.cache.ClassTemplateLoader;
import freemarker.cache.MultiTemplateLoader;
import freemarker.cache.TemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

/**
 * Sends Rundeck job notification messages to a Slack room.
 *
 * @author Hayden Bakkum
 * @author etlweather
 */
@Plugin(service= "Notification", name="MattermostNotification")
@PluginDescription(title="Mattermost Incoming WebHook", description="Sends Rundeck Notifications to Mattermost")
public class SlackNotificationPlugin implements NotificationPlugin
{

    private static final String SLACK_MESSAGE_COLOR_GREEN = "good";
    private static final String SLACK_MESSAGE_COLOR_YELLOW = "warning";
    private static final String SLACK_MESSAGE_COLOR_RED = "danger";
    private static final String SLACK_MESSAGE_FROM_NAME = "rundeck";
    private static final String SLACK_MESSAGE_TEMPLATE = "slack-incoming-message.ftl";

    private static final String TRIGGER_START = "start";
    private static final String TRIGGER_SUCCESS = "success";
    private static final String TRIGGER_FAILURE = "failure";

    private Map<String, SlackNotificationData> TRIGGER_NOTIFICATION_DATA = new HashMap<String, SlackNotificationData>();
    private Configuration freemarkerCfg = new Configuration();

    @PluginProperty(title = "WebHook URL", description = "Mattermost Incoming WebHook URL", required = true)
    private String webhook_url;

    @PluginProperty(title = "Icon URL", description = "URL for an icon to show in Mattermost", required = false)
    private String iconUrl;

    /**
     * Sends a message to a Mattermost channel when a job notification event is raised by Rundeck.
     *
     * @param aTrigger name of job notification event causing notification
     * @param aExecutionData job execution data
     * @param aConfig plugin configuration
     * @throws MattermostNotificationPluginException when any error occurs sending the Slack message
     * @return true, if the Slack API response indicates a message was successfully delivered to a chat room
     */
    public boolean postNotification (String aTrigger, Map aExecutionData, Map aConfig)
    {

        ClassTemplateLoader myBuiltInTemplate = new ClassTemplateLoader(SlackNotificationPlugin.class, "/templates");
        TemplateLoader[] myLoaders = new TemplateLoader[]{myBuiltInTemplate};
        MultiTemplateLoader myMultiTemplateLoader = new MultiTemplateLoader(myLoaders);
        freemarkerCfg.setTemplateLoader (myMultiTemplateLoader);

        TRIGGER_NOTIFICATION_DATA.put(TRIGGER_START,   new SlackNotificationData(SLACK_MESSAGE_TEMPLATE, SLACK_MESSAGE_COLOR_YELLOW));
        TRIGGER_NOTIFICATION_DATA.put(TRIGGER_SUCCESS, new SlackNotificationData(SLACK_MESSAGE_TEMPLATE, SLACK_MESSAGE_COLOR_GREEN));
        TRIGGER_NOTIFICATION_DATA.put(TRIGGER_FAILURE, new SlackNotificationData(SLACK_MESSAGE_TEMPLATE, SLACK_MESSAGE_COLOR_RED));

        try {
            freemarkerCfg.setSetting (Configuration.CACHE_STORAGE_KEY, "strong:20, soft:250");
        }
        catch(Exception e) {
            System.err.printf("Got and exception from Freemarker: %s", e.getMessage());
        }

        if (!TRIGGER_NOTIFICATION_DATA.containsKey(aTrigger)) {
            throw new IllegalArgumentException("Unknown trigger type: [" + aTrigger + "].");
        }

        String message = generateMessage(aTrigger, aExecutionData, aConfig);
        String slackResponse = invokeSlackAPIMethod(webhook_url, message);

        if ("ok".equals(slackResponse)) {
            return true;
        } else {
            // Unfortunately there seems to be no way to obtain a reference to the plugin logger within notification plugins,
            // but throwing an exception will result in its message being logged.
            throw new MattermostNotificationPluginException ("Unknown status returned from Mattermost API: [" + slackResponse + "]." + "\n" + message);
        }
    }

   /**
    * Processes the template into a string representing the payload to send to Mattermost.
    * @param trigger
    * @param executionData
    * @param config
    * @return
    */
    private String generateMessage(String trigger, Map executionData, Map config)
    {
        String templateName = TRIGGER_NOTIFICATION_DATA.get(trigger).template;
        String color = TRIGGER_NOTIFICATION_DATA.get(trigger).color;

        HashMap<String, Object> model = new HashMap<String, Object>();
        model.put("trigger", trigger);
        model.put("color", color);
        model.put("executionData", executionData);
        model.put("config", config);
        model.put("username", SLACK_MESSAGE_FROM_NAME);
        if(iconUrl != null && !iconUrl.isEmpty()) {
            model.put("icon_url", iconUrl);
        }

        StringWriter sw = new StringWriter();
        try {
            Template template = freemarkerCfg.getTemplate (templateName);
            template.process(model,sw);

        } catch (IOException ioEx) {
            throw new MattermostNotificationPluginException ("Error loading Slack notification message template: [" + ioEx.getMessage () + "].", ioEx);
        } catch (TemplateException templateEx) {
            throw new MattermostNotificationPluginException ("Error merging Slack notification message template: [" + templateEx.getMessage () + "].", templateEx);
        }

        return sw.toString();
    }

    private String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException unsupportedEncodingException) {
            throw new MattermostNotificationPluginException ("URL encoding error: [" + unsupportedEncodingException.getMessage () + "].", unsupportedEncodingException);
        }
    }

    // private String invokeSlackAPIMethod(String teamDomain, String token, String message) {
    private String invokeSlackAPIMethod(String webhook_url, String message) {
        URL requestUrl = toURL(webhook_url);

        HttpURLConnection connection = null;
        InputStream responseStream = null;
        String body = "payload=" + URLEncoder.encode(message);
        try {
            connection = openConnection(requestUrl);
            putRequestStream(connection, body);
            responseStream = getResponseStream(connection);
            return getSlackResponse(responseStream);

        } finally {
            closeQuietly(responseStream);
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private URL toURL(String url) {
        try {
            return new URL(url);
        } catch (MalformedURLException malformedURLEx) {
            throw new MattermostNotificationPluginException ("Slack API URL is malformed: [" + malformedURLEx.getMessage () + "].", malformedURLEx);
        }
    }

    private HttpURLConnection openConnection(URL requestUrl) {
        try {
            return (HttpURLConnection) requestUrl.openConnection();
        } catch (IOException ioEx) {
            throw new MattermostNotificationPluginException ("Error opening connection to Slack URL: [" + ioEx.getMessage () + "].", ioEx);
        }
    }

    private void putRequestStream(HttpURLConnection connection, String message) {
        try {
            connection.setRequestMethod("POST");
//            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("charset", "utf-8");

            connection.setDoInput(true);
            connection.setDoOutput(true);
            DataOutputStream wr = new DataOutputStream(connection.getOutputStream());
            wr.writeBytes(message);
            wr.flush();
            wr.close();
        } catch (IOException ioEx) {
            throw new MattermostNotificationPluginException ("Error putting data to Slack URL: [" + ioEx.getMessage () + "].", ioEx);
        }
    }

    private InputStream getResponseStream(HttpURLConnection connection) {
        InputStream input = null;
        try {
            input = connection.getInputStream();
        } catch (IOException ioEx) {
            input = connection.getErrorStream();
        }
        return input;
    }

    private int getResponseCode(HttpURLConnection connection) {
        try {
            return connection.getResponseCode();
        } catch (IOException ioEx) {
            throw new MattermostNotificationPluginException ("Failed to obtain HTTP response: [" + ioEx.getMessage () + "].", ioEx);
        }
    }

    private String getSlackResponse(InputStream responseStream) {
        try {
            return new Scanner(responseStream,"UTF-8").useDelimiter("\\A").next();
        } catch (Exception ioEx) {
            throw new MattermostNotificationPluginException ("Error reading Slack API JSON response: [" + ioEx.getMessage () + "].", ioEx);
        }
    }

    private void closeQuietly(InputStream input) {
        if (input != null) {
            try {
                input.close();
            } catch (IOException ioEx) {
                // ignore
            }
        }
    }

    private static class SlackNotificationData {
        private String template;
        private String color;
        public SlackNotificationData(String template, String color) {
            this.color = color;
            this.template = template;
        }
    }

}
