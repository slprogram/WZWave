/*
 *******************************************************************************
 * Copyright (c) 2013 Whizzo Software, LLC.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************
*/
package com.whizzosoftware.wzwave.controller.netty;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.whizzosoftware.wzwave.channel.ACKInboundHandler;
import com.whizzosoftware.wzwave.channel.FrameQueueHandler;
import com.whizzosoftware.wzwave.channel.TransactionInboundHandler;
import com.whizzosoftware.wzwave.channel.ZWaveChannelInboundHandler;
import com.whizzosoftware.wzwave.channel.ZWaveChannelListener;
import com.whizzosoftware.wzwave.channel.event.SendDataTransactionCompletedEvent;
import com.whizzosoftware.wzwave.channel.event.SendDataTransactionFailedEvent;
import com.whizzosoftware.wzwave.channel.event.TransactionCompletedEvent;
import com.whizzosoftware.wzwave.channel.event.TransactionFailedEvent;
import com.whizzosoftware.wzwave.channel.event.TransactionStartedEvent;
import com.whizzosoftware.wzwave.codec.ZWaveFrameDecoder;
import com.whizzosoftware.wzwave.codec.ZWaveFrameEncoder;
import com.whizzosoftware.wzwave.commandclass.MultilevelSensorCommandClass;
import com.whizzosoftware.wzwave.commandclass.MultilevelSwitchCommandClass;
import com.whizzosoftware.wzwave.commandclass.WakeUpCommandClass;
import com.whizzosoftware.wzwave.controller.ZWaveController;
import com.whizzosoftware.wzwave.controller.ZWaveControllerContext;
import com.whizzosoftware.wzwave.controller.ZWaveControllerListener;
import com.whizzosoftware.wzwave.frame.AddNodeToNetwork;
import com.whizzosoftware.wzwave.frame.ApplicationCommand;
import com.whizzosoftware.wzwave.frame.ApplicationUpdate;
import com.whizzosoftware.wzwave.frame.DataFrame;
import com.whizzosoftware.wzwave.frame.InitData;
import com.whizzosoftware.wzwave.frame.MemoryGetId;
import com.whizzosoftware.wzwave.frame.NodeProtocolInfo;
import com.whizzosoftware.wzwave.frame.OutboundDataFrame;
import com.whizzosoftware.wzwave.frame.RemoveNodeFromNetwork;
import com.whizzosoftware.wzwave.frame.Version;
import com.whizzosoftware.wzwave.node.NodeCreationException;
import com.whizzosoftware.wzwave.node.NodeInfo;
import com.whizzosoftware.wzwave.node.NodeListener;
import com.whizzosoftware.wzwave.node.ZWaveEndpoint;
import com.whizzosoftware.wzwave.node.ZWaveNode;
import com.whizzosoftware.wzwave.node.ZWaveNodeFactory;
import com.whizzosoftware.wzwave.node.generic.MultilevelSensor;
import com.whizzosoftware.wzwave.node.specific.MultilevelPowerSwitch;
import com.whizzosoftware.wzwave.persist.PersistentStore;
import com.whizzosoftware.wzwave.persist.mapdb.MapDbPersistentStore;
import com.whizzosoftware.wzwave.util.ByteUtil;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.jsc.JSerialCommChannel;
import io.netty.channel.jsc.JSerialCommChannelConfig;
import io.netty.channel.jsc.JSerialCommDeviceAddress;
import io.netty.channel.oio.OioEventLoopGroup;

/**
 * A Netty implementation of a ZWaveController.
 *
 * The pipeline looks like this:
 *
 *                                                      I/O Request via Channel or
 *                                                        ChannelHandlerContext
 *                                                                  |
 * +----------------------------------------------------------------+-------------------+
 * |                                      ChannelPipeline           |                   |
 * |                                                                |                   |
 * |    +--------------------------------+                          |                   |
 * |    |    ZWaveChannelInboundHandler  |                          |                   |
 * |    +---------------+----------------+                          |                   |
 * |                   /|\                                          |                   |
 * |                    |                                           |                   |
 * |    +---------------+----------------+                          |                   |
 * |    |    TransactionInboundHandler   |                          |                   |
 * |    +---------------+----------------+                          |                   |
 * |                   /|\                                          |                   |
 * |                    |                                          \|/                  |
 * |    +---------------+-------------------------------------------+--------------+    |
 * |    |                                FrameQueueHandler                         |    |
 * |    +---------------+-------------------------------------------+--------------+    |
 * |                   /|\                                          |                   |
 * |                    |                                           |                   |
 * |    +---------------+----------------+                          |                   |
 * |    |       ACKInboundHandler        |                          |                   |
 * |    +---------------+----------------+                          |                   |
 * |                   /|\                                          |                   |
 * |                    |                                           |                   |
 * |    +---------------+----------------+            +-------------+--------------+    |
 * |    |       ZWaveFrameDecoder        |            |      ZWaveFrameEncoder     |    |
 * |    +---------------+----------------+            +-------------+--------------+    |
 * |                   /|\                                          |                   |
 * +--------------------+-------------------------------------------+-------------------+
 * |                    |                                          \|/                  |
 * +--------------------+-------------------------------------------+-------------------+
 * |                    |                                           |                   |
 * |            [ Socket.read() ]                           [ Socket.write() ]          |
 * +------------------------------------------------------------------------------------+
 *
 * @author Dan Noguerol
 */
public class NettyZWaveController implements ZWaveController, ZWaveControllerContext, ZWaveControllerListener, ZWaveChannelListener, NodeListener {
    private static final Logger logger = LoggerFactory.getLogger(NettyZWaveController.class);

    private String serialPort;
    private PersistentStore store;
    private boolean autoCloseStore;
    private Channel channel;
    private EventLoopGroup eventLoopGroup;
    private String libraryVersion;
    private Integer homeId;
    private Byte nodeId;
    private ZWaveChannelInboundHandler inboundHandler;
    private ZWaveControllerListener listener;
    private final List<ZWaveNode> nodes = new ArrayList<>();
    private final Map<Byte,ZWaveNode> nodeMap = new HashMap<>();

    /**
     * Constructor.
     *
     * @param serialPort the serial port the Z-Wave controller is accessible from
     * @param dataDirectory a directory in which to store persistent data
     */
    public NettyZWaveController(String serialPort, File dataDirectory) {
        this(serialPort, new MapDbPersistentStore(dataDirectory), true);
    }

    /**
     * Constructor.
     *
     * @param serialPort the serial port for Z-Wave controller is accessible from
     * @param store the persistent store to use for storing/retrieving node information
     */
    public NettyZWaveController(String serialPort, PersistentStore store) {
        this(serialPort, store, false);
    }

    /**
     * Constructor.
     *
     * @param serialPort the serial port for Z-Wave controller is accessible from
     * @param store the persistent store to use for storing/retrieving node information
     * @param autoCloseStore indicates whether store should be automatically closed when controller is stopped
     */
    public NettyZWaveController(String serialPort, PersistentStore store, boolean autoCloseStore) {
        this.serialPort = serialPort;
        this.store = store;
        this.autoCloseStore = autoCloseStore;
        this.inboundHandler = new ZWaveChannelInboundHandler(this);
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    /*
     * ZWaveController methods
     */

    @Override
    public void setListener(ZWaveControllerListener listener) {
        this.listener = listener;
    }

    @Override
    public void start() {
        // make sure there is a persistence store available
        if (store == null) {
            throw new RuntimeException("No persistence store available. Either one was not provided or controller start() was called after stop().");
        }

        // ignore the start call if the controller was already started
        if (channel == null) {
            // set up Netty bootstrap
            Bootstrap bootstrap = new Bootstrap();
            eventLoopGroup = new OioEventLoopGroup();
            bootstrap.group(eventLoopGroup);
            bootstrap.channel(JSerialCommChannel.class);
            bootstrap.handler(new ChannelInitializer<JSerialCommChannel>() {
                @Override
                protected void initChannel(JSerialCommChannel channel) throws Exception {
                    NettyZWaveController.this.channel = channel;
                    channel.config().setBaudrate(115200);
                    channel.config().setDatabits(8);
                    channel.config().setParitybit(JSerialCommChannelConfig.Paritybit.NONE);
                    channel.config().setStopbits(JSerialCommChannelConfig.Stopbits.STOPBITS_1);
                    channel.pipeline().addLast("decoder", new ZWaveFrameDecoder());
                    channel.pipeline().addLast("ack", new ACKInboundHandler());
                    channel.pipeline().addLast("encoder", new ZWaveFrameEncoder());
                    channel.pipeline().addLast("writeQueue", new FrameQueueHandler());
                    channel.pipeline().addLast("transaction", new TransactionInboundHandler());
                    channel.pipeline().addLast("handler", inboundHandler);
                }
            });

            bootstrap.connect(new JSerialCommDeviceAddress(serialPort)).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        sendDataFrame(new Version());
                        sendDataFrame(new MemoryGetId());
                        sendDataFrame(new InitData());
                    } else {
                        shutdown();
                        onZWaveConnectionFailure(future.cause());
                    }
                }
            });
        }
    }

    @Override
    public void stop() {
        shutdown();
    }

    private void shutdown() {
        // clean up the persistence store
        if (store != null && autoCloseStore) {
            store.close();
        }
        store = null;

        // clean up the channel
        if (channel != null) {
            channel.close();
            channel = null;
        }

        // shutdown the event loop group
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
        }
    }

    @Override
    public int getHomeId() {
        return homeId;
    }

    @Override
    public byte getNodeId() {
        return nodeId;
    }

    @Override
    public String getLibraryVersion() {
        return libraryVersion;
    }

    @Override
    public Collection<ZWaveNode> getNodes() {
        return nodes;
    }

    @Override
    public ZWaveNode getNode(byte nodeId) {
        return nodeMap.get(nodeId);
    }

    public void sendDataFrame(DataFrame frame) {
        channel.write(new OutboundDataFrame(frame, true));
    }

    public void sendDataFrame(DataFrame frame, boolean isListeningNode) {
        channel.write(new OutboundDataFrame(frame, isListeningNode));
    }

    @Override
    public void sendEvent(Object e) {
        channel.write(e);
    }

    /*
     * ZWaveControllerListener methods
     */

    @Override
    public void onZWaveNodeAdded(ZWaveEndpoint node) {
        if (listener != null) {
            listener.onZWaveNodeAdded(node);
        }
    }

    @Override
    public void onZWaveNodeUpdated(ZWaveEndpoint node) {
        if (listener != null) {
            listener.onZWaveNodeUpdated(node);
        }
    }

    @Override
    public void onZWaveConnectionFailure(Throwable t) {
        if (listener != null) {
            listener.onZWaveConnectionFailure(t);
        } else {
            logger.error("Connection failure and no listener was set", t);
        }
    }

    @Override
    public void onZWaveControllerInfo(String libraryVersion, Integer homeId, Byte nodeId) {
        if (listener != null && libraryVersion != null && homeId != null && nodeId != null) {
            listener.onZWaveControllerInfo(libraryVersion, homeId, nodeId);
        }
    }

    @Override
    public void onZWaveInclusionStarted() {
        if (listener != null) {
            listener.onZWaveInclusionStarted();
        }
    }

    @Override
    public void onZWaveInclusion(NodeInfo nodeInfo, boolean success) {
        try {
            logger.trace("Inclusion of new node {}", ByteUtil.createString(nodeInfo.getNodeId()));
            ZWaveNode node = ZWaveNodeFactory.createNode(nodeInfo, !nodeInfo.hasCommandClass(WakeUpCommandClass.ID), this);
            logger.trace("Created new node [{}]: {}", node.getNodeId(), node);
            addNode(node);
            if (listener != null) {
                listener.onZWaveInclusion(nodeInfo, success);
            }
        } catch (NodeCreationException e) {
            logger.error("Unable to create node", e);
        }
    }

    @Override
    public void onZWaveInclusionStopped() {
        if (listener != null) {
            listener.onZWaveInclusionStopped();
        }
    }

    @Override
    public void onZWaveExclusionStarted() {
        if (listener != null) {
            listener.onZWaveExclusionStarted();
        }
    }

    @Override
    public void onZWaveExclusion(NodeInfo nodeInfo, boolean success) {
        if (listener != null) {
            listener.onZWaveExclusion(nodeInfo, success);
        }
    }

    @Override
    public void onZWaveExclusionStopped() {
        if (listener != null) {
            listener.onZWaveExclusionStopped();
        }
    }

    /*
     * ZWaveChannelListener methods
     */

    @Override
    public void onLibraryInfo(String libraryVersion) {
        this.libraryVersion = libraryVersion;
        onZWaveControllerInfo(libraryVersion, homeId, nodeId);
    }

    @Override
    public void onControllerInfo(int homeId, byte nodeId) {
        this.homeId = homeId;
        this.nodeId = nodeId;
        onZWaveControllerInfo(libraryVersion, homeId, nodeId);
    }

    @Override
    public void onNodeProtocolInfo(byte nodeId, NodeProtocolInfo npi) {
        try {
            logger.trace("Received protocol info for node {}", nodeId);
            ZWaveNode node = store.getNode(nodeId, this);
            if (node == null || !node.matchesNodeProtocolInfo(npi)) {
                node = ZWaveNodeFactory.createNode(
                    new NodeInfo(nodeId, npi.getBasicDeviceClass(), npi.getGenericDeviceClass(), npi.getSpecificDeviceClass()),
                    npi.isListening(),
                    this
                );
                logger.trace("Created new node: {}: {}", nodeId, node);
            } else {
                logger.debug("Node[{}] matches persistent node information; no need to interview", nodeId);
            }
            addNode(node);
        } catch (NodeCreationException e) {
            logger.error("Unable to create node", e);
        }
    }

    private void addNode(ZWaveNode node) {
        ZWaveNode n = nodeMap.get(node.getNodeId());
        if (n != null) {
            nodes.remove(n);
            nodeMap.remove(node.getNodeId());
        }
        nodes.add(node);
        nodeMap.put(node.getNodeId(), node);
        node.startInterview(this);
    }

    @Override
    public void onApplicationCommand(ApplicationCommand applicationCommand) {
        ZWaveNode node = nodeMap.get(applicationCommand.getNodeId());
        if (node != null) {
            node.onApplicationCommand(this, applicationCommand);
            if (node.isStarted()) {
                onZWaveNodeUpdated(node);
            }
        } else {
            logger.error("Unable to find node: {}", nodeId);
        }
    }

    @Override
    public void onApplicationUpdate(ApplicationUpdate applicationUpdate) {
        Byte nodeId = applicationUpdate.getNodeId();

        if (applicationUpdate.didInfoRequestFail()) {
            logger.trace("UPDATE_STATE_NODE_INFO_REQ_FAILED received");
        }

        if (nodeId != null) {
            ZWaveNode node = nodeMap.get(nodeId);
            if (node != null) {
                node.onApplicationUpdate(this, applicationUpdate);
                if (node.isStarted()) {
                    onZWaveNodeUpdated(node);
                }
            } else {
                logger.error("Unable to find node: {}", nodeId);
            }
        } else {
            logger.error("Unable to determine node to route ApplicationUpdate to");
        }
    }

    @Override
    public void onTransactionStarted(TransactionStartedEvent evt) {
        logger.trace("Detected start of new transaction: {}", evt.getId());
        channel.write(evt);
    }

    @Override
    public void onTransactionComplete(TransactionCompletedEvent evt) {
        logger.trace("Detected end of transaction: {}", evt.getId());
        if (evt instanceof SendDataTransactionCompletedEvent) {
            ZWaveNode node = nodeMap.get(evt.getNodeId());
            if (node != null) {
                node.onSendDataCallback(this, true);
            } else {
                logger.error("Unable to find node: {}", evt.getNodeId());
            }
        }
        channel.write(evt);
    }

    @Override
    public void onTransactionFailed(TransactionFailedEvent evt) {
        logger.trace("Detected transaction failure: {}", evt.getId());
        if (evt instanceof SendDataTransactionFailedEvent) {
            ZWaveNode node = nodeMap.get(evt.getNodeId());
            if (node != null) {
                node.onSendDataCallback(this, ((SendDataTransactionFailedEvent)evt).isTargetNodeACKReceived());
            } else {
                logger.error("Unable to find node: {}", evt.getNodeId());
            }
        }
        channel.write(evt);
    }

    @Override
    public void onAddNodeToNetwork(AddNodeToNetwork update) {
        if (listener != null) {
            switch (update.getStatus()) {
                case AddNodeToNetwork.ADD_NODE_STATUS_LEARN_READY:
                    onZWaveInclusionStarted();
                    break;
                case AddNodeToNetwork.ADD_NODE_STATUS_DONE:
                    onZWaveInclusionStopped();
                    break;
                case AddNodeToNetwork.ADD_NODE_STATUS_ADDING_CONTROLLER:
                case AddNodeToNetwork.ADD_NODE_STATUS_ADDING_SLAVE:
                    onZWaveInclusion(update.getNodeInfo(), true);
                    break;
                case AddNodeToNetwork.ADD_NODE_STATUS_FAILED:
                    onZWaveInclusion(update.getNodeInfo(), false);
                    break;
                default:
                    logger.debug("Received unexpected status from AddNodeToNetwork frame: {}", update.getStatus());
            }
        }
    }

    @Override
    public void onRemoveNodeFromNetwork(RemoveNodeFromNetwork update) {
        if (listener != null) {
            switch (update.getStatus()) {
                case RemoveNodeFromNetwork.REMOVE_NODE_STATUS_LEARN_READY:
                    onZWaveExclusionStarted();
                    break;
                case RemoveNodeFromNetwork.REMOVE_NODE_STATUS_DONE:
                    onZWaveExclusionStopped();
                    break;
                case RemoveNodeFromNetwork.REMOVE_NODE_STATUS_NODE_FOUND:
                    logger.debug("A node has been found that wants to be excluded: {}", ByteUtil.createString(update.getSource()));
                    break;
                case RemoveNodeFromNetwork.REMOVE_NODE_STATUS_REMOVING_CONTROLLER:
                case RemoveNodeFromNetwork.REMOVE_NODE_STATUS_REMOVING_SLAVE:
                    onZWaveExclusion(update.getNodeInfo(), true);
                    break;
                case RemoveNodeFromNetwork.REMOVE_NODE_STATUS_FAILED:
                    onZWaveExclusion(update.getNodeInfo(), false);
                    break;
                default:
                    logger.debug("Received unexpected status from RemoveNodeFromNetwork frame: {}", update.getStatus());
            }
        }
    }

    @Override
    public void onSetDefault() {
        logger.info("Z-Wave controller has been reset to factory default");
    }

    /*
     * NodeListener methods
     */

    @Override
    public void onNodeStarted(ZWaveNode node) {
        // save the newly started node
        logger.debug("Saving information for node {}", node.getNodeId());
        store.saveNode(node);

        // when a node moves to the "started" state, alert listeners that it's ready to be added
        onZWaveNodeAdded(node);
    }
    
    public static void main(String[] args) throws InterruptedException
	{
    	NettyZWaveController controller = new NettyZWaveController("COM3", new File("C:\\programmation\\zwave"));
    	controller.start();
    	Thread.sleep(5000);
    	Collection<ZWaveNode> nodeList = controller.getNodes();
    	while (nodeList.isEmpty())
    	{
    		System.out.println("Sleep 1s");
        	Thread.sleep(1000);
        	nodeList = controller.getNodes();
    	}
    	for (ZWaveNode node : nodeList) {
    		if (node instanceof MultilevelPowerSwitch)
    		{
    			MultilevelPowerSwitch multilevelPowerSwitch = (MultilevelPowerSwitch) node;
    			MultilevelSensorCommandClass sensorCommand = (MultilevelSensorCommandClass) multilevelPowerSwitch.getCommandClass(MultilevelSensorCommandClass.ID);
    			System.out.println(sensorCommand.getType() + "=" + sensorCommand.getValues() + " " + sensorCommand.getScale());
    			MultilevelSwitchCommandClass multilevelSwitchCommand = (MultilevelSwitchCommandClass) node.getCommandClass(MultilevelSwitchCommandClass.ID);
    			System.out.println("level=" + multilevelSwitchCommand.getLevel());
    		}
    		else if (node instanceof MultilevelSensor) {

        		System.out.println(node);
    		}
    	}
    	controller.stop();
	}
}
