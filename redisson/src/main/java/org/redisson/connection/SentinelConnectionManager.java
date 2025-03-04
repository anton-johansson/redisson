/**
 * Copyright (c) 2013-2021 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.connection;

import io.netty.resolver.AddressResolver;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.StringUtil;
import org.redisson.api.NatMapper;
import org.redisson.api.NodeType;
import org.redisson.api.RFuture;
import org.redisson.client.*;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.config.*;
import org.redisson.connection.ClientConnectionsEntry.FreezeReason;
import org.redisson.misc.AsyncCountDownLatch;
import org.redisson.misc.RPromise;
import org.redisson.misc.RedisURI;
import org.redisson.misc.RedissonPromise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * 
 * @author Nikita Koksharov
 *
 */
public class SentinelConnectionManager extends MasterSlaveConnectionManager {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final Set<RedisURI> sentinelHosts = new HashSet<>();
    private final ConcurrentMap<RedisURI, RedisClient> sentinels = new ConcurrentHashMap<>();
    private final AtomicReference<RedisURI> currentMaster = new AtomicReference<>();

    private final Set<RedisURI> disconnectedSlaves = new HashSet<>();
    private ScheduledFuture<?> monitorFuture;
    private AddressResolver<InetSocketAddress> sentinelResolver;

    private final NatMapper natMapper;

    private final String sentinelPassword;
    private boolean usePassword = false;
    private String scheme;
    private boolean checkSlaveStatusWithSyncing;
    private boolean sentinelsDiscovery;

    public SentinelConnectionManager(SentinelServersConfig cfg, Config config, UUID id) {
        super(config, id);
        
        if (cfg.getMasterName() == null) {
            throw new IllegalArgumentException("masterName parameter is not defined!");
        }
        if (cfg.getSentinelAddresses().isEmpty()) {
            throw new IllegalArgumentException("At least one sentinel node should be defined!");
        }

        this.config = create(cfg);
        this.sentinelPassword = cfg.getSentinelPassword();
        this.checkSlaveStatusWithSyncing = cfg.isCheckSlaveStatusWithSyncing();
        this.sentinelsDiscovery = cfg.isSentinelsDiscovery();
        initTimer(this.config);

        this.natMapper = cfg.getNatMapper();

        this.sentinelResolver = resolverGroup.getResolver(getGroup().next());

        for (String address : cfg.getSentinelAddresses()) {
            RedisURI addr = new RedisURI(address);
            scheme = addr.getScheme();
            addr = applyNatMap(addr);
            if (NetUtil.createByteArrayFromIpAddressString(addr.getHost()) == null && !addr.getHost().equals("localhost")) {
                sentinelHosts.add(addr);
            }
        }

        checkAuth(cfg);

        Throwable lastException = null;
        for (String address : cfg.getSentinelAddresses()) {
            RedisURI addr = new RedisURI(address);
            addr = applyNatMap(addr);

            RedisClient client = createClient(NodeType.SENTINEL, addr, this.config.getConnectTimeout(), this.config.getTimeout(), null);
            try {
                RedisConnection connection = null;
                try {
                    connection = client.connect();
                    if (!connection.isActive()) {
                        continue;
                    }
                } catch (RedisConnectionException e) {
                    continue;
                }
                
                RedisURI master = connection.sync(RedisCommands.SENTINEL_GET_MASTER_ADDR_BY_NAME, cfg.getMasterName());
                if (master == null) {
                    throw new RedisConnectionException("Master node is undefined! SENTINEL GET-MASTER-ADDR-BY-NAME command returns empty result!");
                }

                RedisURI masterHost = resolveIP(scheme, master).syncUninterruptibly().getNow();
                this.config.setMasterAddress(masterHost.toString());
                currentMaster.set(masterHost);
                log.info("master: {} added", masterHost);

                List<Map<String, String>> sentinelSlaves = connection.sync(StringCodec.INSTANCE, RedisCommands.SENTINEL_SLAVES, cfg.getMasterName());
                for (Map<String, String> map : sentinelSlaves) {
                    if (map.isEmpty()) {
                        continue;
                    }

                    String host = map.get("ip");
                    String port = map.get("port");
                    String flags = map.getOrDefault("flags", "");
                    String masterLinkStatus = map.getOrDefault("master-link-status", "");

                    RedisURI uri = resolveIP(host, port).syncUninterruptibly().getNow();

                    this.config.addSlaveAddress(uri.toString());
                    log.debug("slave {} state: {}", uri, map);
                    log.info("slave: {} added", uri);

                    if (isSlaveDown(flags, masterLinkStatus)) {
                        disconnectedSlaves.add(uri);
                        log.warn("slave: {} is down", uri);
                    }
                }
                
                List<Map<String, String>> sentinelSentinels = connection.sync(StringCodec.INSTANCE, RedisCommands.SENTINEL_SENTINELS, cfg.getMasterName());
                List<RFuture<Void>> connectionFutures = new ArrayList<>(sentinelSentinels.size());
                for (Map<String, String> map : sentinelSentinels) {
                    if (map.isEmpty()) {
                        continue;
                    }

                    String ip = map.get("ip");
                    String port = map.get("port");

                    RedisURI uri = resolveIP(ip, port).syncUninterruptibly().getNow();
                    RFuture<Void> future = registerSentinel(uri, this.config, null);
                    connectionFutures.add(future);
                }

                RedisURI sentinelIp = toURI(connection.getRedisClient().getAddr());
                RFuture<Void> f = registerSentinel(sentinelIp, this.config, null);
                connectionFutures.add(f);

                for (RFuture<Void> future : connectionFutures) {
                    future.awaitUninterruptibly(this.config.getConnectTimeout());
                }

                break;
            } catch (RedisConnectionException e) {
                stopThreads();
                throw e;
            } catch (Exception e) {
                lastException = e;
                log.warn(e.getMessage());
            } finally {
                client.shutdownAsync();
            }
        }

        if (cfg.isCheckSentinelsList() && cfg.isSentinelsDiscovery()) {
            if (sentinels.isEmpty()) {
                stopThreads();
                throw new RedisConnectionException("SENTINEL SENTINELS command returns empty result! Set checkSentinelsList = false to avoid this check.", lastException);
            } else if (sentinels.size() < 2) {
                stopThreads();
                throw new RedisConnectionException("SENTINEL SENTINELS command returns less than 2 nodes! At least two sentinels should be defined in Redis configuration. Set checkSentinelsList = false to avoid this check.", lastException);
            }
        }
        
        if (currentMaster.get() == null) {
            stopThreads();
            throw new RedisConnectionException("Can't connect to servers!", lastException);
        }
        if (this.config.getReadMode() != ReadMode.MASTER && this.config.getSlaveAddresses().isEmpty()) {
            log.warn("ReadMode = " + this.config.getReadMode() + ", but slave nodes are not found!");
        }
        
        initSingleEntry();
        
        scheduleChangeCheck(cfg, null);
    }

    private void checkAuth(SentinelServersConfig cfg) {
        if (cfg.getPassword() == null) {
            return;
        }

        for (String address : cfg.getSentinelAddresses()) {
            RedisURI addr = new RedisURI(address);
            addr = applyNatMap(addr);

            RedisClient client = createClient(NodeType.SENTINEL, addr, this.config.getConnectTimeout(), this.config.getTimeout(), null);
            try {
                RedisConnection c = client.connect();
                if (config.getPingConnectionInterval() == 0) {
                    c.sync(RedisCommands.PING);
                }
                return;
            } catch (RedisAuthRequiredException e) {
                usePassword = true;
                return;
            } catch (RedisConnectionException e) {
                log.warn("Can't connect to sentinel server", e);
            } catch (Exception e) {
                // skip
            } finally {
                client.shutdown();
            }
        }
        
        stopThreads();
        StringBuilder list = new StringBuilder();
        for (String address : cfg.getSentinelAddresses()) {
            list.append(address).append(", ");
        }
        throw new RedisConnectionException("Unable to connect to Redis sentinel servers: " + list);
    }
    
    @Override
    protected void startDNSMonitoring(RedisClient masterHost) {
        if (config.getDnsMonitoringInterval() == -1 || sentinelHosts.isEmpty()) {
            return;
        }
        
        scheduleSentinelDNSCheck();
    }
    
    @Override
    protected RedisClientConfig createRedisConfig(NodeType type, RedisURI address, int timeout, int commandTimeout,
            String sslHostname) {
        RedisClientConfig result = super.createRedisConfig(type, address, timeout, commandTimeout, sslHostname);
        if (type == NodeType.SENTINEL && !usePassword) {
            result.setUsername(null);
            result.setPassword(null);
        } else if (type == NodeType.SENTINEL && usePassword) {
            result.setUsername(null);
            if (sentinelPassword != null) {
                result.setPassword(sentinelPassword);
            }
        }
        return result;
    }

    private void scheduleSentinelDNSCheck() {
        monitorFuture = group.schedule(new Runnable() {
            @Override
            public void run() {
                AtomicInteger sentinelsCounter = new AtomicInteger(sentinelHosts.size());
                performSentinelDNSCheck(future -> {
                    if (sentinelsCounter.decrementAndGet() == 0) {
                        scheduleSentinelDNSCheck();
                    }
                });
            }
        }, config.getDnsMonitoringInterval(), TimeUnit.MILLISECONDS);
    }

    private void performSentinelDNSCheck(FutureListener<List<InetSocketAddress>> commonListener) {
        for (RedisURI host : sentinelHosts) {
            Future<List<InetSocketAddress>> allNodes = sentinelResolver.resolveAll(InetSocketAddress.createUnresolved(host.getHost(), host.getPort()));
            allNodes.addListener((FutureListener<List<InetSocketAddress>>) future -> {
                if (!future.isSuccess()) {
                    log.error("Unable to resolve " + host.getHost(), future.cause());
                    return;
                }

                future.getNow().stream()
                        .map(addr -> toURI(addr))
                        .filter(uri -> !sentinels.containsKey(uri))
                        .forEach(uri -> registerSentinel(uri, getConfig(), host.getHost()));
            });
            if (commonListener != null) {
                allNodes.addListener(commonListener);
            }
        }
    }
    
    private void scheduleChangeCheck(SentinelServersConfig cfg, Iterator<RedisClient> iterator) {
        monitorFuture = group.schedule(new Runnable() {
            @Override
            public void run() {
                AtomicReference<Throwable> lastException = new AtomicReference<Throwable>();
                Iterator<RedisClient> iter = iterator;
                if (iter == null) {
                    // Shuffle the list so all clients don't prefer the same sentinel
                    List<RedisClient> clients = new ArrayList<>(sentinels.values());
                    Collections.shuffle(clients);
                    iter = clients.iterator();
                }
                checkState(cfg, iter, lastException);
            }
        }, cfg.getScanInterval(), TimeUnit.MILLISECONDS);
    }

    private void checkState(SentinelServersConfig cfg, Iterator<RedisClient> iterator, AtomicReference<Throwable> lastException) {
        if (!iterator.hasNext()) {
            if (lastException.get() != null) {
                log.error("Can't update cluster state", lastException.get());
            }
            performSentinelDNSCheck(null);
            scheduleChangeCheck(cfg, null);
            return;
        }
        if (!getShutdownLatch().acquire()) {
            return;
        }

        RedisClient client = iterator.next();
        RedisURI addr = toURI(client.getAddr());
        RFuture<RedisConnection> connectionFuture = connectToNode(NodeType.SENTINEL, cfg, addr, null);
        connectionFuture.onComplete((connection, e) -> {
            if (e != null) {
                lastException.set(e);
                getShutdownLatch().release();
                checkState(cfg, iterator, lastException);
                return;
            }

            updateState(cfg, connection, iterator);
        });

    }

    private void updateState(SentinelServersConfig cfg, RedisConnection connection, Iterator<RedisClient> iterator) {
        AtomicInteger commands = new AtomicInteger(2);
        BiConsumer<Object, Throwable> commonListener = new BiConsumer<Object, Throwable>() {
            
            private final AtomicBoolean failed = new AtomicBoolean();
            
            @Override
            public void accept(Object t, Throwable u) {
                if (commands.decrementAndGet() == 0) {
                    getShutdownLatch().release();
                    if (failed.get()) {
                        scheduleChangeCheck(cfg, iterator);
                    } else {
                        scheduleChangeCheck(cfg, null);
                    }
                }
                if (u != null && failed.compareAndSet(false, true)) {
                    log.error("Can't execute SENTINEL commands on " + connection.getRedisClient().getAddr(), u);
                    closeNodeConnection(connection);
                }
            }
        };

        RFuture<RedisURI> masterFuture = checkMasterChange(cfg, connection);
        masterFuture.onComplete(commonListener);

        if (!config.checkSkipSlavesInit()) {
            commands.incrementAndGet();
            RFuture<List<Map<String, String>>> slavesFuture = checkSlavesChange(cfg, connection);
            slavesFuture.onComplete(commonListener);
        }

        RFuture<List<Map<String, String>>> sentinelsFuture = checkSentinelsChange(cfg, connection);
        sentinelsFuture.onComplete(commonListener);
    }

    private RFuture<List<Map<String, String>>> checkSentinelsChange(SentinelServersConfig cfg, RedisConnection connection) {
        if (!sentinelsDiscovery) {
            return RedissonPromise.newSucceededFuture(null);
        }

        RFuture<List<Map<String, String>>> sentinelsFuture = connection.async(StringCodec.INSTANCE, RedisCommands.SENTINEL_SENTINELS, cfg.getMasterName());
        sentinelsFuture.onComplete((list, e) -> {
            if (e != null || list.isEmpty()) {
                return;
            }

            AsyncCountDownLatch latch = new AsyncCountDownLatch();
            List<RFuture<RedisURI>> newUris = list.stream().filter(m -> {
                String flags = m.getOrDefault("flags", "");
                String masterLinkStatus = m.getOrDefault("master-link-status", "");
                if (!m.isEmpty() && !isSlaveDown(flags, masterLinkStatus)) {
                    return true;
                }
                return false;
            }).map(m -> {
                String ip = m.get("ip");
                String port = m.get("port");
                return toURI(ip, port);
            }).map(addr -> {
                RFuture<RedisURI> f = resolveIP(addr);
                f.onComplete((res, ex) -> {
                    if (ex != null) {
                        log.error("unable to resolve hostname", ex);
                    }
                    latch.countDown();
                });
                return f;
            }).collect(Collectors.toList());

            latch.latch(() -> {
                List<RedisURI> uris = newUris.stream().map(u -> u.getNow()).filter(u -> u != null).collect(Collectors.toList());
                InetSocketAddress addr = connection.getRedisClient().getAddr();
                RedisURI currentAddr = toURI(addr);
                uris.add(currentAddr);

                updateSentinels(uris);
            }, newUris.size());
        });
        return sentinelsFuture;
    }

    private RFuture<List<Map<String, String>>> checkSlavesChange(SentinelServersConfig cfg, RedisConnection connection) {
        RFuture<List<Map<String, String>>> slavesFuture = connection.async(StringCodec.INSTANCE, RedisCommands.SENTINEL_SLAVES, cfg.getMasterName());
        slavesFuture.onComplete((slavesMap, ex) -> {
            if (ex != null) {
                return;
            }

            Set<RedisURI> currentSlaves = new HashSet<>(slavesMap.size());
            AsyncCountDownLatch latch = new AsyncCountDownLatch();
            for (Map<String, String> map : slavesMap) {
                if (map.isEmpty()) {
                    latch.countDown();
                    continue;
                }

                String host = map.get("ip");
                String port = map.get("port");
                String flags = map.getOrDefault("flags", "");
                String masterLinkStatus = map.getOrDefault("master-link-status", "");
                String masterHost = map.get("master-host");
                String masterPort = map.get("master-port");

                RFuture<RedisURI> slaveAddrFuture = resolveIP(host, port);
                RFuture<RedisURI> masterAddrFuture;
                if ("?".equals(masterHost)) {
                    masterAddrFuture = RedissonPromise.newSucceededFuture(null);
                } else {
                    masterAddrFuture = resolveIP(masterHost, masterPort);
                }
                CompletableFuture<Void> resolvedFuture = CompletableFuture.allOf(masterAddrFuture.toCompletableFuture(),
                                                                                    slaveAddrFuture.toCompletableFuture());
                resolvedFuture.whenComplete((res, exc) -> {
                    if (exc != null) {
                        log.error("Unable to resolve addresses " + host + " and/or " + masterHost, exc);
                        latch.countDown();
                        return;
                    }

                    RedisURI slaveAddr = slaveAddrFuture.getNow();
                    RedisURI masterAddr = masterAddrFuture.getNow();
                    if (isSlaveDown(flags, masterLinkStatus)) {
                        slaveDown(slaveAddr);
                        latch.countDown();
                        return;
                    }
                    if ("?".equals(masterHost) || !isUseSameMaster(slaveAddr, masterAddr)) {
                        latch.countDown();
                        return;
                    }

                    currentSlaves.add(slaveAddr);
                    addSlave(slaveAddr).onComplete((r, e2) -> {
                        latch.countDown();
                        if (e2 != null) {
                            log.error("Unable to add slave " + slaveAddr, e2);
                        }
                    });
                });
            }

            latch.latch(() -> {
                MasterSlaveEntry entry = getEntry(singleSlotRange.getStartSlot());
                entry.getAllEntries().stream()
                        .map(e -> e.getClient().getAddr())
                        .map(a -> toURI(a.getAddress().getHostAddress(), String.valueOf(a.getPort())))
                        .filter(a -> !currentSlaves.contains(a) && !a.equals(currentMaster.get()))
                        .forEach(a -> slaveDown(a));
            }, slavesMap.size());
        });
        return slavesFuture;
    }

    private RFuture<RedisURI> checkMasterChange(SentinelServersConfig cfg, RedisConnection connection) {
        RFuture<RedisURI> masterFuture = connection.async(StringCodec.INSTANCE, RedisCommands.SENTINEL_GET_MASTER_ADDR_BY_NAME, cfg.getMasterName());
        masterFuture.thenCompose(u -> resolveIP(scheme, u))
                .whenComplete((newMaster, e) -> {
            if (e != null) {
                return;
            }

            RedisURI current = currentMaster.get();
            if (!newMaster.equals(current)
                    && currentMaster.compareAndSet(current, newMaster)) {
                RFuture<RedisClient> changeFuture = changeMaster(singleSlotRange.getStartSlot(), newMaster);
                changeFuture.onComplete((res, ex) -> {
                    if (ex != null) {
                        currentMaster.compareAndSet(newMaster, current);
                    }
                });
            }
        });
        return masterFuture;
    }

    private void updateSentinels(Collection<RedisURI> newUris) {
        newUris.stream()
                .filter(uri -> !sentinels.containsKey(uri))
                .forEach(uri -> registerSentinel(uri, getConfig(), null));

        sentinels.keySet().stream()
                .filter(uri -> !newUris.contains(uri))
                .forEach(uri -> {
                    RedisClient sentinel = sentinels.remove(uri);
                    if (sentinel != null) {
                        disconnectNode(uri);
                        sentinel.shutdownAsync();
                        log.warn("sentinel: {} is down", uri);
                    }
                });
    }

    private RedisURI toURI(String host, String port) {
        // convert IPv6 address to unified compressed format
        if (NetUtil.isValidIpV6Address(host)) {
            byte[] addr = NetUtil.createByteArrayFromIpAddressString(host);
            try {
                InetAddress ia = InetAddress.getByAddress(host, addr);
                host = ia.getHostAddress();
            } catch (UnknownHostException e) {
                throw new RuntimeException(e);
            }
        }
        RedisURI uri = new RedisURI(scheme + "://" + host + ":" + port);
        return applyNatMap(uri);
    }

    @Override
    protected Collection<RedisURI> getDisconnectedNodes() {
        return disconnectedSlaves;
    }

    private RFuture<Void> registerSentinel(RedisURI addr, MasterSlaveServersConfig c, String sslHostname) {
        boolean isHostname = NetUtil.createByteArrayFromIpAddressString(addr.getHost()) == null;
        if (!isHostname) {
            RedisClient sentinel = sentinels.get(addr);
            if (sentinel != null) {
                return RedissonPromise.newSucceededFuture(null);
            }
        }

        RedisClient client = createClient(NodeType.SENTINEL, addr, c.getConnectTimeout(), c.getTimeout(), sslHostname);
        RPromise<Void> result = new RedissonPromise<Void>();
        RFuture<InetSocketAddress> future = client.resolveAddr();
        future.onComplete((res, e) -> {
            if (e != null) {
                result.tryFailure(e);
                return;
            }

            RedisURI ipAddr = toURI(client.getAddr());
            if (isHostname) {
                RedisClient sentinel = sentinels.get(ipAddr);
                if (sentinel != null) {
                    result.trySuccess(null);
                    return;
                }
            }

            RFuture<RedisConnection> f = client.connectAsync();
            f.onComplete((connection, ex) -> {
                if (ex != null) {
                    result.tryFailure(ex);
                    return;
                }

                RFuture<String> r = connection.async(RedisCommands.PING);
                r.onComplete((resp, exc) -> {
                    if (exc != null) {
                        result.tryFailure(exc);
                        return;
                    }
                    
                    if (sentinels.putIfAbsent(ipAddr, client) == null) {
                        log.info("sentinel: {} added", ipAddr);
                    }
                    result.trySuccess(null);
                });
            });

        });
        return result;
    }

    private RFuture<RedisURI> resolveIP(String host, String port) {
        RedisURI uri = toURI(host, port);
        return resolveIP(uri);
    }

    private RedisURI toURI(InetSocketAddress addr) {
        return toURI(addr.getAddress().getHostAddress(), "" + addr.getPort());
    }

    private RFuture<Void> addSlave(RedisURI uri) {
        RPromise<Void> result = new RedissonPromise<Void>();
        // to avoid addition twice
        MasterSlaveEntry entry = getEntry(singleSlotRange.getStartSlot());
        if (!entry.hasSlave(uri) && !config.checkSkipSlavesInit()) {
            RFuture<Void> future = entry.addSlave(uri);
            future.onComplete((res, e) -> {
                if (e != null) {
                    result.tryFailure(e);
                    log.error("Can't add slave: " + uri, e);
                    return;
                }

                if (entry.isSlaveUnfreezed(uri) || entry.slaveUp(uri, FreezeReason.MANAGER)) {
                    log.info("slave: {} added", uri);
                    result.trySuccess(null);
                }
            });
        } else {
            if (entry.hasSlave(uri)) {
                slaveUp(uri);
            }
            result.trySuccess(null);
        }
        return result;
    }

    private void slaveDown(RedisURI uri) {
        if (config.checkSkipSlavesInit()) {
            log.warn("slave: {} is down", uri);
        } else {
            MasterSlaveEntry entry = getEntry(singleSlotRange.getStartSlot());
            if (entry.slaveDown(uri, FreezeReason.MANAGER)) {
                log.warn("slave: {} is down", uri);
            }
        }
    }

    private boolean isSlaveDown(String flags, String masterLinkStatus) {
        boolean baseStatus = flags.contains("s_down") || flags.contains("disconnected");
        if (this.checkSlaveStatusWithSyncing && !StringUtil.isNullOrEmpty(masterLinkStatus)) {
            return baseStatus || masterLinkStatus.contains("err");
        }
        return baseStatus;
    }

    private boolean isUseSameMaster(RedisURI slaveAddr, RedisURI slaveMasterAddr) {
        RedisURI master = currentMaster.get();
        if (!master.equals(slaveMasterAddr)) {
            log.warn("Skipped slave up {} for master {} differs from current {}", slaveAddr, slaveMasterAddr, master);
            return false;
        }
        return true;
    }
    
    private void slaveUp(RedisURI uri) {
        if (config.checkSkipSlavesInit()) {
            log.info("slave: {} is up", uri);
            return;
        }

        if (getEntry(singleSlotRange.getStartSlot()).slaveUp(uri, FreezeReason.MANAGER)) {
            log.info("slave: {} is up", uri);
        }
    }

    @Override
    protected MasterSlaveServersConfig create(BaseMasterSlaveServersConfig<?> cfg) {
        MasterSlaveServersConfig res = super.create(cfg);
        res.setDatabase(((SentinelServersConfig) cfg).getDatabase());
        return res;
    }
    
    public Collection<RedisClient> getSentinels() {
        return sentinels.values();
    }

    @Override
    public void shutdown() {
        if (monitorFuture != null) {
            monitorFuture.cancel(true);
        }

        sentinels.values().stream()
                .map(s -> s.shutdownAsync())
                .forEach(f -> f.syncUninterruptibly());

        super.shutdown();
    }

    @Override
    public RedisURI applyNatMap(RedisURI address) {
        RedisURI result = natMapper.map(address);
        if (!result.equals(address)) {
            log.debug("nat mapped uri: {} to {}", address, result);
        }
        return result;
    }

}

