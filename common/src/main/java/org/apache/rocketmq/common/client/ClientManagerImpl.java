/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.common.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.common.ThreadFactoryImpl;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.remoting.RemotingChannel;
import org.apache.rocketmq.remoting.common.RemotingHelper;
import org.apache.rocketmq.remoting.netty.NettyChannelHandlerContextImpl;
import org.apache.rocketmq.remoting.netty.NettyChannelImpl;

public abstract class ClientManagerImpl implements ClientManager {

    private static final InternalLogger log = InternalLoggerFactory
        .getLogger(LoggerName.SNODE_LOGGER_NAME);
    private static final long CHANNEL_EXPIRED_TIMEOUT = 1000 * 120;
    private final ScheduledExecutorService scheduledExecutorService = Executors
        .newSingleThreadScheduledExecutor(
            new ThreadFactoryImpl("ClientHousekeepingScheduledThread"));

    private final ConcurrentHashMap<String/*Producer or Consumer Group*/, ConcurrentHashMap<RemotingChannel, Client>> groupClientTable = new ConcurrentHashMap<String, ConcurrentHashMap<RemotingChannel, Client>>(
        1024);

    public abstract void onClosed(String group, RemotingChannel remotingChannel);

    public abstract void onUnregister(String group, RemotingChannel remotingChannel);

    public abstract void onRegister(String group, RemotingChannel remotingChannel);

    public void startScan(long interval) {
        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    scanExpiredChannel();
                } catch (Throwable e) {
                    log.error("Error occurred when scan not active client channels.", e);
                }
            }
        }, 1000 * 10, interval, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        if (this.scheduledExecutorService != null) {
            this.scheduledExecutorService.shutdown();
        }
    }

    public void scanExpiredChannel() {
        Iterator iterator = groupClientTable.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry entry = (Map.Entry) iterator.next();
            String group = (String) entry.getKey();
            ConcurrentHashMap<RemotingChannel, Client> channelTable = (ConcurrentHashMap<RemotingChannel, Client>) entry
                .getValue();
            Iterator iter = channelTable.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry channelTableEntry = (Map.Entry) iter.next();
                Client client = (Client) channelTableEntry.getValue();
                long interval = System.currentTimeMillis() - client.getLastUpdateTimestamp();
                if (interval > CHANNEL_EXPIRED_TIMEOUT) {
                    iter.remove();
                    client.getRemotingChannel().close();
                    log.warn(
                        "SCAN: Remove expired channel from {}ClientTable. channel={}, group={}",
                        client.getClientRole(),
                        RemotingHelper.parseChannelRemoteAddr(
                            client.getRemotingChannel().remoteAddress()), group);
                    if (channelTable.isEmpty()) {
                        iterator.remove();
                        log.warn("SCAN: Remove group={} channel from {}ClientTable.", group,
                            client.getClientRole());
                    }
                }
            }
        }
    }

    @Override
    public boolean register(String groupId, Client client) {
        boolean updated = false;
        if (client != null) {
            ConcurrentHashMap<RemotingChannel, Client> channelTable = this.groupClientTable.get(groupId);
            if (channelTable == null) {
                channelTable = new ConcurrentHashMap();
                ConcurrentHashMap prev = groupClientTable.putIfAbsent(groupId, channelTable);
                channelTable = prev != null ? prev : channelTable;
            }
            RemotingChannel remotingChannel = client.getRemotingChannel();
            if (remotingChannel instanceof NettyChannelHandlerContextImpl) {
                remotingChannel = new NettyChannelImpl(((NettyChannelHandlerContextImpl) remotingChannel).getChannelHandlerContext().channel());
            }
            Client oldClient = channelTable.get(remotingChannel);
            if (oldClient == null) {
                Client prev = channelTable.put(remotingChannel, client);
                if (prev != null) {
                    log.info("New client connected, group: {} {} {} channel: {}", groupId,
                        client.toString());
                    updated = true;
                }
                oldClient = client;
            } else {
                if (!oldClient.getClientId().equals(client.getClientId())) {
                    log.error(
                        "[BUG] client channel exist in snode, but clientId not equal. GROUP: {} OLD: {} NEW: {} ",
                        groupId,
                        oldClient.toString(),
                        channelTable.toString());
                    channelTable.put(remotingChannel, client);
                }
            }
            oldClient.setLastUpdateTimestamp(System.currentTimeMillis());
            onRegister(groupId, remotingChannel);
        }
        log.debug("Register client role: {}, group: {}, last: {}", client.getClientRole(), groupId,
            client.getLastUpdateTimestamp());
        return updated;
    }

    protected void removeClient(String groupId, RemotingChannel remotingChannel) {
        ConcurrentHashMap<RemotingChannel, Client> channelTable = groupClientTable.get(groupId);
        if (channelTable != null) {
            Client prev = channelTable.remove(remotingChannel);
            if (prev != null) {
                log.info("Unregister client: {}  in  group, {}", prev, groupId);
            }
            if (channelTable.isEmpty()) {
                groupClientTable.remove(groupId);
                log.info("Unregister client ok, no any connection, and remove consumer group, {}",
                    groupId);
            }
        }
    }

    @Override
    public void unRegister(String groupId, RemotingChannel remotingChannel) {
        removeClient(groupId, remotingChannel);
        onUnregister(groupId, remotingChannel);

    }

    @Override
    public void onClose(Set<String> groups, RemotingChannel remotingChannel) {
        for (String groupId : groups) {
            removeClient(groupId, remotingChannel);
            onClosed(groupId, remotingChannel);
        }
    }

    public List<RemotingChannel> getChannels(String groupId) {
        if (groupId != null) {
            List<RemotingChannel> result = new ArrayList<RemotingChannel>();
            ConcurrentHashMap channelsMap = this.groupClientTable.get(groupId);
            if (channelsMap != null) {
                result.addAll(this.groupClientTable.get(groupId).keySet());
                return result;
            }
            return null;
        }
        return null;
    }

    @Override
    public List<String> getAllClientId(String groupId) {
        List<String> result = new ArrayList<String>();
        Map<RemotingChannel, Client> channelClientMap = this.groupClientTable.get(groupId);
        if (channelClientMap != null) {
            Iterator<Map.Entry<RemotingChannel, Client>> it = channelClientMap.entrySet()
                .iterator();
            while (it.hasNext()) {
                Map.Entry<RemotingChannel, Client> entry = it.next();
                Client client = entry.getValue();
                result.add(client.getClientId());
            }
        }
        return result;
    }

    @Override
    public Client getClient(String groupId, RemotingChannel remotingChannel) {
        assert groupId != null && remotingChannel != null;
        if (!groupClientTable.containsKey(groupId)) {
            return null;
        }
        ConcurrentHashMap<RemotingChannel, Client> channelClientMap = groupClientTable
            .get(groupId);
        if (remotingChannel instanceof NettyChannelHandlerContextImpl) {
            remotingChannel = new NettyChannelImpl(((NettyChannelHandlerContextImpl) remotingChannel).getChannelHandlerContext().channel());
        }
        return channelClientMap.get(remotingChannel);
    }
}
