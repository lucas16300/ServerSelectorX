package xyz.derkades.serverselectorx;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import xyz.derkades.derkutils.Cooldown;
import xyz.derkades.derkutils.bukkit.Colors;
import xyz.derkades.derkutils.bukkit.IconMenu;
import xyz.derkades.derkutils.bukkit.ItemBuilder;
import xyz.derkades.serverselectorx.utils.ServerPinger;
import xyz.derkades.serverselectorx.utils.ServerPinger.Server;

public class SelectorMenu extends IconMenu {

	private FileConfiguration config;
	private Player player;
	
	public SelectorMenu(Player player, FileConfiguration config) {
		super(Main.getPlugin(), Colors.parseColors(config.getString("title", "no title")), config.getInt("rows", 6) * 9, player);
		
		this.config = config;
		this.player = player;
	}

	@Override
	public void open() {
		new BukkitRunnable(){
			public void run(){
				for (final String key : config.getConfigurationSection("menu").getKeys(false)) {
					final ConfigurationSection section = config.getConfigurationSection("menu." + key);
					
					final ItemBuilder builder = new ItemBuilder(Material.STONE);
					
					if (!section.getBoolean("ping-server")){
						//Server pinging is turned off, get item info from 'online' section
						Bukkit.getScheduler().runTask(Main.getPlugin(), () -> { //Go back to main thread
							Material material = Material.getMaterial(section.getString("online.item"));
							if (material == null) material = Material.STONE;
							builder.type(material);
							builder.data(section.getInt("online.data", 0));
							builder.name(Main.PLACEHOLDER_API.parsePlaceholders(player, section.getString("online.name", "error")));
							builder.lore(Main.PLACEHOLDER_API.parsePlaceholders(player, section.getStringList("online.lore")));
							
							//Apply custom glowing enchantment
							if (section.getBoolean("online.enchanted", false))
								builder.unsafeEnchant(new GlowEnchantment(), 1);
							
							//Add item
							items.put(Integer.valueOf(key), builder.create());
						});
						continue;
					} else {
						//Server pinging is turned on, ping server asynchronously
						String ip = section.getString("ip");
						int port = section.getInt("port");
						
						Server server;
						
						if (Main.getPlugin().getConfig().getBoolean("external-query", true)){
							server = new ServerPinger.ExternalServer(ip, port);
						} else {
							int timeout = section.getInt("ping-timeout", 100);
							server = new ServerPinger.InternalServer(ip, port, timeout);
						}
						
						boolean online = server.isOnline();
						String motd = server.getMotd();
						int onlinePlayers = server.getOnlinePlayers();
						int maxPlayers = server.getMaximumPlayers();
						int ping = server.getResponseTimeMillis();
						
						//No need to run async anymore
						
						Bukkit.getScheduler().runTask(Main.getPlugin(), () -> {
							Material material = Material.STONE;
							int data = 0;
							String name = "";
							List<String> lore = new ArrayList<>();
							int amount = 1;
							boolean enchanted = false;
							
							if (online) {
								//Server is online, try dynamic motd items first 
								boolean motdMatch = false;
								if (section.contains("dynamic")) {
									for (String dynamicMotd : section.getConfigurationSection("dynamic").getKeys(false)) {
										if (motd.equals(dynamicMotd)) {
											//Motd matches, use this section for getting item data
											motdMatch = true;
											ConfigurationSection motdSection = section.getConfigurationSection("dynamic." + dynamicMotd);
											
											material = Material.getMaterial(motdSection.getString("item"));
											data = motdSection.getInt("data", 0);
											name = motdSection.getString("name");
											lore = motdSection.getStringList("lore");
											enchanted = motdSection.getBoolean("enchanted", false);
										} else {
											continue; //No match, check next motd in this section
										}
									}
								}
								
								if (!motdMatch) {
									//If no motd matched, fall back to online
									material = Material.getMaterial(section.getString("online.item"));
									data = section.getInt("online.data", 0);
									name = section.getString("online.name", "error");
									lore = section.getStringList("online.lore");
									enchanted = section.getBoolean("online.enchanted", false);
								}
								
								//Replace placeholders in lore
								lore = replaceInStringList(lore, 
										new Object[] {"{online}", "{max}", "{motd}", "{ping}"},
										new Object[] {onlinePlayers, maxPlayers, motd, ping});
								
								if (section.getBoolean("change-item-count", true)) {
									String mode = Main.getPlugin().getConfig().getString("item-count-mode", "absolute");									
									if (mode.equals("absolute")) {
										amount = onlinePlayers;
									} else if (mode.equals("relative")) {
										amount = (onlinePlayers / maxPlayers) * 100;
									} else {
										amount = 1;
										Main.getPlugin().getLogger().warning("item-count-mode setting is invalid");
									}
										
									if (amount > 64 || amount < 1)
										amount = 1;
								}
							} else {
								//Server is offline
								ConfigurationSection offlineSection = section.getConfigurationSection("offline");
								
								material = Material.getMaterial(offlineSection.getString("item"));
								data = offlineSection.getInt("data", 0);
								name = offlineSection.getString("name");
								lore = offlineSection.getStringList("lore");
								enchanted = offlineSection.getBoolean("enchanted", false);
							}
							
							if (material == null) material = Material.STONE;
							builder.type(material);
							builder.data(data);
							builder.name(Main.PLACEHOLDER_API.parsePlaceholders(player, name));
							builder.lore(Main.PLACEHOLDER_API.parsePlaceholders(player, lore));
							
							//Apply custom glowing enchantment
							if (enchanted) builder.unsafeEnchant(new GlowEnchantment(), 1);
							
							//Add item to menu
							items.put(Integer.valueOf(key), builder.create());
						});
					}
				}
				
				//After everything has completed, open menu synchronously
				Bukkit.getScheduler().runTaskLater(Main.getPlugin(), () -> {
					Cooldown.addCooldown(config.getName() + player.getName(), 0); //Remove cooldown if menu opened successfully
					callOriginalOpenMethod();
				}, 1); //Wait a tick just in case. It's unnoticeable anyways
			}
		}.runTaskAsynchronously(Main.getPlugin());
		
	}
	
	private void callOriginalOpenMethod(){
		super.open();
	}

	@Override
	public boolean onOptionClick(OptionClickEvent event) {		
		int slot = event.getPosition();
		Player player = event.getPlayer();
		
		String action = config.getString("menu." + slot + ".action");
		
		if (action.startsWith("url:")){ //Send url message
			//It's a URL
			String url = action.substring(4);
			String message = Colors.parseColors(config.getString("url-message", "&3&lClick here"));
			
			player.spigot().sendMessage(
					new ComponentBuilder(message)
					.event(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
					.create()
					);
			return true;
		} else if (action.startsWith("cmd:")){ //Execute command
			//It's a command
			String command = action.substring(4);
			
			//Send command 1 tick later to let the GUI close first (for commands that open a GUI)
			Bukkit.getScheduler().scheduleSyncDelayedTask(Main.getPlugin(), () -> {
				Bukkit.dispatchCommand(player, command);
			}, 1);
			return true;
		} else if (action.startsWith("bungeecmd:")) { //BungeeCord command
			String command = action.split(":")[0];
			Bukkit.dispatchCommand(Bukkit.getConsoleSender(), String.format("sync player %s %s", player.getName(), command));
			return true;
		} else if (action.startsWith("sel:")){ //Open selector
			//It's a server selector
			String configName = action.substring(4);
			FileConfiguration config = Main.getSelectorConfigurationFile(configName);
			if (config == null){
				player.sendMessage(ChatColor.RED + "This server selector does not exist.");
				return true;
			} else {				
				new SelectorMenu(player, config).open();
				
				return false;
			}
		} else if (action.startsWith("world:")){ //Teleport to world
			String worldName = action.substring(6);
			World world = Bukkit.getWorld(worldName);
			if (world == null){
				player.sendMessage(ChatColor.RED + "A world with the name " + worldName + " does not exist.");
				return true;
			} else {
				player.teleport(world.getSpawnLocation());
				return true;
			}
		} else if (action.startsWith("srv:")){ //Teleport to server
			String serverName = action.substring(4);
			Main.teleportPlayerToServer(player, serverName);
			return true;
		} else if (action.startsWith("msg")){ //Send message
			String message = action.substring(4);
			player.sendMessage(Colors.parseColors(message));
			return true;
		} else if (action.equals("close")){ //Close selector
			return true; //Return true = close
		} else {
			return false; //Return false = stay open
		}
	
	}
	
	private List<String> replaceInStringList(List<String> list, Object[] before, Object[] after) {
		if (before.length != after.length) {
			throw new IllegalArgumentException("before[] length must be equal to after[] length");
		}
		
		List<String> newList = new ArrayList<>();
		
		for (String string : list) {
			for (int i = 0; i < before.length; i++) {
				string = string.replace(before[i].toString(), after[i].toString());
			}
			newList.add(string);
		}
		
		return newList;
	}

}
