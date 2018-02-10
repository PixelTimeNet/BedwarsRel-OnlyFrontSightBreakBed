/*
 * Copyright (C) 2017 The MoonLake Authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */


package com.minecraft.moonlake.onlyfrontsightbreakbed;

import com.minecraft.moonlake.MoonLakeAPI;
import com.minecraft.moonlake.MoonLakePlugin;
import com.minecraft.moonlake.api.event.MoonLakeListener;
import com.minecraft.moonlake.api.packet.listener.PacketListenerFactory;
import com.minecraft.moonlake.api.packet.listener.handler.PacketHandler;
import com.minecraft.moonlake.api.packet.listener.handler.PacketOption;
import com.minecraft.moonlake.api.packet.listener.handler.PacketReceived;
import com.minecraft.moonlake.api.packet.listener.handler.PacketSent;
import com.minecraft.moonlake.exception.MoonLakeException;
import com.minecraft.moonlake.nms.packet.PacketFactory;
import com.minecraft.moonlake.reflect.Reflect;
import com.minecraft.moonlake.util.StringUtil;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.Level;

public class Main extends JavaPlugin implements MoonLakeListener {

    private Map<String, Integer> SUSPECTEDBREAKBEDCOUNTMAP;
    private PacketHandler PACKETHANDLER;
    private Class<?> CLASS_CRAFTWORLD;
    private Class<?> CLASS_BLOCKPOSITION;
    private Class<?> CLASS_ENUMDIRECTION;
    private Class<?> CLASS_BASEBLOCKPOSITION;
    private Class<?> CLASS_PACKETPLAYOUTBLOCKCHANGE;
    private Method METHOD_GETHANDLER;
    private Method METHOD_SHIFT;
    private Method METHOD_GETX;
    private Method METHOD_GETY;
    private Method METHOD_GETZ;
    private String PREFIX;

    public Main() {
    }

    @Override
    public void onEnable() {
        if(!setupMoonLake()) {
            this.getLogger().log(Level.SEVERE, "前置月色之湖核心API插件加载失败.");
            this.getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.initFolder();
        this.initReflect();
        this.initPacketListener();
        this.getServer().getPluginManager().registerEvents(this, this);
        this.getLogger().info("只能准星破坏床 OnlyFrontSightBreakBed 插件 v" + getDescription().getVersion() + " 成功加载.");
    }

    @Override
    public void onDisable() {
        // 卸载数据包监听器以及释放变量
        PacketListenerFactory.removeHandler(PACKETHANDLER);
        SUSPECTEDBREAKBEDCOUNTMAP.clear();
        SUSPECTEDBREAKBEDCOUNTMAP = null;
        CLASS_PACKETPLAYOUTBLOCKCHANGE = null;
        CLASS_BASEBLOCKPOSITION = null;
        CLASS_BLOCKPOSITION = null;
        CLASS_CRAFTWORLD = null;
        METHOD_GETX = null;
        METHOD_GETY = null;
        METHOD_GETZ = null;
        METHOD_SHIFT = null;
        PACKETHANDLER = null;
        PREFIX = null;
    }

    private void initReflect() {
        // 初始化反射
        try {
            CLASS_PACKETPLAYOUTBLOCKCHANGE = Reflect.PackageType.MINECRAFT_SERVER.getClass("PacketPlayOutBlockChange");
            CLASS_BASEBLOCKPOSITION = Reflect.PackageType.MINECRAFT_SERVER.getClass("BaseBlockPosition");
            CLASS_ENUMDIRECTION = Reflect.PackageType.MINECRAFT_SERVER.getClass("EnumDirection");
            CLASS_BLOCKPOSITION = Reflect.PackageType.MINECRAFT_SERVER.getClass("BlockPosition");
            CLASS_CRAFTWORLD = Reflect.PackageType.CRAFTBUKKIT.getClass("CraftWorld");
            METHOD_SHIFT = Reflect.getMethod(CLASS_BLOCKPOSITION, "shift", CLASS_ENUMDIRECTION);
            METHOD_GETHANDLER = Reflect.getMethod(CLASS_CRAFTWORLD, "getHandle");
            METHOD_GETX = Reflect.getMethod(CLASS_BASEBLOCKPOSITION, "getX");
            METHOD_GETY = Reflect.getMethod(CLASS_BASEBLOCKPOSITION, "getY");
            METHOD_GETZ = Reflect.getMethod(CLASS_BASEBLOCKPOSITION, "getZ");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "初始化反射源数据时错误, 异常信息:", e);
            getServer().getPluginManager().disablePlugin(this);
        }
        SUSPECTEDBREAKBEDCOUNTMAP = new HashMap<>(); // init
    }

    private void initPacketListener() {
        // 初始化数据包监听器
        PACKETHANDLER = new PacketHandler(this) {
            @Override
            public void onSend(PacketSent packet) {
            }

            @Override
            @PacketOption(forcePlayer = true)
            public void onReceive(PacketReceived packet) {
                // 处理服务端接收到的数据包
                if(!packet.hasPlayer()) return;
                if(!packet.getPacketName().equals("PacketPlayInBlockDig")) return;
                // 只有服务端接收到的数据包为破坏方块则处理

                // 获取破坏方块的玩家
                Player player = packet.getPlayer();
                // 检测玩家是否拥有权限则返回
                if(player.hasPermission("moonlake.ofsbb.ignore")) return;

                // 获取破坏的方块并判断是否符合床
                Block breakBlock = getBlock(player, packet.getPacketValue("a"));
                if(breakBlock.getType() != Material.BED_BLOCK) return;

                // 获取玩家准星的方块
                Block frontSightBlock = player.getTargetBlock((Set<Material>) null, 5);

                // 对比破坏的方块和准星的方块是否符合
                if(breakBlock.getType() != frontSightBlock.getType()) {
                    // 疑似作弊: 玩家破坏的方块和准星对应的方块不符合 (freecam? nuker?)
                    packet.setCancelled(true); // 阻止数据包
                    player.sendMessage(getWarnMessage()); // 发送警告消息
                    sendBlockChange(player, breakBlock, packet); // 发送方块恢复数据包以更新玩家客户端方块数据
                    handlerStatistics(player, breakBlock); // 处理疑似作弊破坏次数统计处理
                }
            }
        };
        PacketListenerFactory.addHandler(PACKETHANDLER);
    }

    private void handlerStatistics(Player player, Block breakBlock) {
        // 处理玩家的疑似作弊破坏统计
        if(!isStatisticsHandler()) return; // 没有开启统计功能则返回
        // 获取玩家已被统计的次数
        Integer cache = SUSPECTEDBREAKBEDCOUNTMAP.get(player.getName());
        SUSPECTEDBREAKBEDCOUNTMAP.put(player.getName(), cache = (cache != null ? ++cache : 1));
        // 判断次数是否超过设置的值
        if(cache > getStatisticsHandlerValue()) {
            // 超过则处理
            List<String> handlerList = getStatisticsHandlers();
            if(handlerList.isEmpty()) return; // 如果处理为空则返回
            // 否则进行处理
            for (String handler : handlerList) {
                if (handler.startsWith("CMD:")) {
                    // 命令处理
                    String cmd = handler.substring(4).replaceAll("%player%", player.getName());
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                } else if (handler.startsWith("KICK:")) {
                    // 踢出处理
                    MoonLakeAPI.runTaskLater(this, new Runnable() {
                        @Override
                        public void run() {
                            player.kickPlayer(getMessage(handler.substring(5)));
                        }
                    }, 1L);
                } else if (handler.startsWith("BAN:")) {
                    // 封禁处理
                    MoonLakeAPI.runTaskLater(this, new Runnable() {
                        @Override
                        public void run() {
                            String message = handler.substring(4);
                            player.kickPlayer(getMessage(message));
                            Bukkit.getBanList(BanList.Type.NAME).addBan(player.getName(), message, null, null);
                        }
                    }, 1L);
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // 处理玩家退出事件清除统计
        Player player = event.getPlayer();
        if(SUSPECTEDBREAKBEDCOUNTMAP.containsKey(player.getName()))
            SUSPECTEDBREAKBEDCOUNTMAP.remove(player.getName());
    }

    private Block getBlock(Player player, Object blockPosition) {
        // 获取 BlockPosition 的方块
        if(blockPosition != null) try {
            int x = (int) METHOD_GETX.invoke(blockPosition);
            int y = (int) METHOD_GETY.invoke(blockPosition);
            int z = (int) METHOD_GETZ.invoke(blockPosition);
            return player.getWorld().getBlockAt(x, y, z);
        } catch (Exception e) {
            throw new MoonLakeException(e.getMessage(), e);
        }
        return null;
    }

    private void sendBlockChange(Player player, Block block, PacketReceived packet) {
        // 发送方块改变
        try {
            Object blockPosition = packet.getPacketValue("a");
            Object enumDirection = packet.getPacketValue("b");
            Object nmsWorld = METHOD_GETHANDLER.invoke(block.getWorld());
            Object ppobc1 = Reflect.instantiateObject(CLASS_PACKETPLAYOUTBLOCKCHANGE, nmsWorld, blockPosition);
            Object ppobc2 = Reflect.instantiateObject(CLASS_PACKETPLAYOUTBLOCKCHANGE, nmsWorld, METHOD_SHIFT.invoke(blockPosition, enumDirection));
            // 发送方块改变数据包
            Player[] target = { player };
            PacketFactory.get().sendPacket(target, ppobc1);
            PacketFactory.get().sendPacket(target, ppobc2);
        } catch (Exception e) {
            throw new MoonLakeException(e.getMessage(), e);
        }
    }

    private boolean isStatisticsHandler() {
        // 获取是否开启疑似统计破坏处理
        return getConfig().getBoolean("StatisticsHandler.Enable", true);
    }

    private void initFolder() {
        if(!getDataFolder().exists())
            getDataFolder().mkdirs();
        File config = new File(getDataFolder(), "config.yml");
        if(!config.exists())
            saveDefaultConfig();
        this.PREFIX = getConfig().getString("Prefix", "&f[&cOFSBB&f] ");
    }

    private String getMessage(String message) {
        return StringUtil.toColor(PREFIX + message);
    }

    private String getWarnMessage() {
        return getMessage(getConfig().getString("BreakCancelledWarn", "&6检测到你疑似使用作弊破坏床方块!!!"));
    }

    private int getStatisticsHandlerValue() {
        return getConfig().getInt("StatisticsHandler.Value", 10);
    }

    private List<String> getStatisticsHandlers() {
        List<String> handlerList = new ArrayList<>();
        List<String> configList = getConfig().getStringList("StatisticsHandler.Handler");
        if(configList == null) {
            String single = getConfig().getString("StatisticsHandler.Handler", null);
            if(single != null) handlerList.add(single);
        } else {
            handlerList.addAll(configList);
        }
        return handlerList;
    }

    private boolean setupMoonLake() {
        Plugin plugin = this.getServer().getPluginManager().getPlugin("MoonLake");
        return plugin != null && plugin instanceof MoonLakePlugin;
    }
}
