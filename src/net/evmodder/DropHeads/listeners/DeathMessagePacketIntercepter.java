package net.evmodder.DropHeads.listeners;

import java.util.HashSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.evmodder.DropHeads.DropHeads;
import net.evmodder.DropHeads.JunkUtils;
import net.evmodder.EvLib.extras.ReflectionUtils;
import net.evmodder.EvLib.extras.ReflectionUtils.RefClass;
import net.evmodder.EvLib.extras.ReflectionUtils.RefField;
import net.evmodder.EvLib.extras.ReflectionUtils.RefMethod;

public class DeathMessagePacketIntercepter{
	private final Plugin pl;
	private final boolean REPLACE_PLAYER_DEATH_MSG, REPLACE_PET_DEATH_MSG;
	private final HashSet<UUID> unblockedDeathBroadcasts;
	private final HashSet<String> unblockedSpecificDeathMsgs;
	private final HashSet<String> blockedSpecificMsgs;

	private final RefClass outboundPacketClazz = ReflectionUtils.getRefClass(
			"{nms}.PacketPlayOutChat", "{nm}.network.protocol.game.PacketPlayOutChat", "{nm}.network.protocol.game.ClientboundSystemChatPacket");
	private final RefClass chatBaseCompClazz = ReflectionUtils.getRefClass(
			"{nms}.IChatBaseComponent", "{nm}.network.chat.IChatBaseComponent");
	private final RefClass chatSerializerClazz = ReflectionUtils.getRefClass(
			"{nms}.IChatBaseComponent$ChatSerializer", "{nm}.network.chat.IChatBaseComponent$ChatSerializer");
	private final RefField chatBaseCompField;
	private final RefMethod getChatBaseComp;
	private final RefMethod toJsonMethod = chatSerializerClazz.findMethod(/*isStatic=*/true, String.class, chatBaseCompClazz);
	private final Pattern uuidPattern = Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");

	public DeathMessagePacketIntercepter(boolean replacePlayerDeathMsg, boolean replacePetDeathMsg){
		pl = DropHeads.getPlugin();
		REPLACE_PLAYER_DEATH_MSG = replacePlayerDeathMsg;
		REPLACE_PET_DEATH_MSG = replacePetDeathMsg;
		unblockedDeathBroadcasts = new HashSet<>();
		unblockedSpecificDeathMsgs = new HashSet<>();
		blockedSpecificMsgs = new HashSet<>();
		
		RefField field = null;
		RefMethod method = null;
		try{field = outboundPacketClazz.findField(chatBaseCompClazz);}
		catch(RuntimeException ex){method = outboundPacketClazz.getMethod("content");}
		finally{
			chatBaseCompField = field;
			getChatBaseComp = method;
		}

		pl.getServer().getPluginManager().registerEvents(new Listener(){
			@EventHandler public void onJoin(PlayerJoinEvent evt){injectPlayer(evt.getPlayer());}
			@EventHandler public void onQuit(PlayerQuitEvent evt){removePlayer(evt.getPlayer());}
		}, pl);
		for(Player p : pl.getServer().getOnlinePlayers()) injectPlayer(p);
	}

	public boolean hasDeathMessage(Entity e){
		return e instanceof Player || (
				e instanceof Tameable &&
				((Tameable)e).getOwner() != null &&
				pl.getServer().getEntity(((Tameable)e).getOwner().getUniqueId()) != null
		);
	}

	private void injectPlayer(Player player){
		JunkUtils.getPlayerChannel(player).pipeline().addBefore("packet_handler", player.getName(), new ChannelDuplexHandler(){
			@Override public void write(ChannelHandlerContext context, Object packet, ChannelPromise promise) throws Exception {
				if(!outboundPacketClazz.isInstance(packet)){ // Not a chat packet
					super.write(context, packet, promise);
					return;
				}
				final Object chatBaseComp = chatBaseCompField == null ? packet : chatBaseCompField.of(packet).get();
				if(chatBaseComp == null){ // Chat packet does not have a comp field/method (pre-1.19)
					super.write(context, packet, promise);
					return;
				}
				final String jsonMsg = (String)(chatBaseCompField == null ? getChatBaseComp.of(packet).call() : toJsonMethod.call(chatBaseComp));
				if(jsonMsg == null){ // Chat comp is not a json object
					super.write(context, packet, promise);
					return;
				}
				if(blockedSpecificMsgs.contains(jsonMsg)){
					return;
				}
				if(!jsonMsg.startsWith("{\"translate\":\"death.") || unblockedSpecificDeathMsgs.remove(jsonMsg)){
					super.write(context, packet, promise);
					return;
				}
//				pl.getLogger().info("detected death msg:\n"+jsonMsg);
				final Matcher matcher = uuidPattern.matcher(jsonMsg);
				if(!matcher.find()){
					pl.getLogger().warning("Unable to parse death message: "+jsonMsg);
					super.write(context, packet, promise);
					return;
				}
				final UUID uuid = UUID.fromString(matcher.group()); // uuid of entity which died
				if(unblockedDeathBroadcasts.contains(uuid)){
					super.write(context, packet, promise);
					return;
				}
				new BukkitRunnable(){@Override public void run(){ // server.getEntity(uuid) needs to be called synchronously
					if(unblockedDeathBroadcasts.contains(uuid)){
						JunkUtils.sendPacket(player, packet); // If this death msg has been unblocked
						return;
					}
					final boolean shouldReplaceDeathMsg = pl.getServer().getEntity(uuid) instanceof Player
							? REPLACE_PLAYER_DEATH_MSG : REPLACE_PET_DEATH_MSG;
					if(!shouldReplaceDeathMsg){
						unblockedSpecificDeathMsgs.add(jsonMsg);
						JunkUtils.sendPacket(player, packet);
						return;
					}
					new BukkitRunnable(){@Override public void run(){
						// check again if unblocked 1 tick later
						if(unblockedDeathBroadcasts.contains(uuid)) JunkUtils.sendPacket(player, packet);
//						else pl.getLogger().info("blocked entity death msgs for: "+entity.getName());
					}}.runTaskLater(pl, 1);
				}}.runTask(pl);
			}
		});
	}
	private void removePlayer(Player player){
		final Channel channel = JunkUtils.getPlayerChannel(player);
		channel.eventLoop().submit(()->{
			channel.pipeline().remove(player.getName());
			return null;
		});
	}

	public void unblockDeathMessage(Entity entity){
		if(hasDeathMessage(entity)){
//			pl.getLogger().info("unblocked death msg called");
			unblockedDeathBroadcasts.add(entity.getUniqueId());
			new BukkitRunnable(){@Override public void run(){unblockedDeathBroadcasts.remove(entity.getUniqueId());}}.runTaskLater(pl, 5);
		}
	}

	public void blockSpeficicMessage(String message, long ticksBlockedFor){
		blockedSpecificMsgs.add(message);
		new BukkitRunnable(){@Override public void run(){blockedSpecificMsgs.remove(message);}}.runTaskLater(pl, ticksBlockedFor);
	}

	public void unregisterAll(){
		for(Player player : pl.getServer().getOnlinePlayers()) removePlayer(player);
	}
}
