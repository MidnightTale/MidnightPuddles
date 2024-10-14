package net.hynse.midnightPuddles

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Levelled
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.weather.WeatherChangeEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class MidnightPuddles : JavaPlugin(), Listener {
    private val weatherTasks = ConcurrentHashMap<World, BukkitTask>()
    private val puddles = mutableMapOf<UUID, MutableMap<IntLocation, BukkitTask>>()
    private val modifierKey = NamespacedKey(this, "MidnightPuddles_SpeedBoost")

    override fun onEnable() {
        server.pluginManager.registerEvents(this, this)
        server.worlds.filter { it.hasStorm() || it.isThundering }.forEach(::startWeatherTask)
    }

    override fun onDisable() {
        weatherTasks.values.forEach(BukkitTask::cancel)
        weatherTasks.clear()
        server.onlinePlayers.forEach(::removeSpeedBoostCompletely)
    }

    @EventHandler
    fun onWeatherChange(event: WeatherChangeEvent) {
        val world = event.world
        if (event.toWeatherState()) startWeatherTask(world) else stopWeatherTask(world)
    }

    private fun startWeatherTask(world: World) {
        if (weatherTasks.containsKey(world)) return
        val task = server.scheduler.runTaskTimer(this, Runnable {
            if (!world.hasStorm()) {
                stopWeatherTask(world)
                return@Runnable
            }
            world.players.forEach { spawnPuddle(it, world) }
        }, 0L, 100L)
        weatherTasks[world] = task
    }

    private fun spawnPuddle(player: Player, world: World) {
        val radius = 15
        val playerLoc = player.location
        val location = Location(world,
            playerLoc.x + Random.nextInt(-radius, radius + 1),
            playerLoc.y,
            playerLoc.z + Random.nextInt(-radius, radius + 1)
        )
        val surfaceBlock = world.getHighestBlockAt(location)

        if (isPuddleableBlock(surfaceBlock.type)) {
            val puddleLocation = IntLocation(world, surfaceBlock.x, surfaceBlock.y + 1, surfaceBlock.z)
            val waterLevel = 7
            val duration = 20L * (10L + Random.nextLong(120L))

            player.sendBlockChange(puddleLocation.toBukkitLocation(), createWaterData(waterLevel))
            val puddleLocationDouble = Location(world, puddleLocation.x.toDouble(), puddleLocation.y.toDouble(), puddleLocation.z.toDouble())
            val textDisplay = createTextDisplay(world, puddleLocationDouble, waterLevel, duration)
            player.showEntity(this, textDisplay)

            startTimerUpdateTask(player, textDisplay, duration, puddleLocationDouble, waterLevel)
            val removalTask = schedulePuddleRemoval(player, puddleLocation, textDisplay, duration)

            puddles.getOrPut(player.uniqueId) { mutableMapOf() }[puddleLocation] = removalTask
        }
    }

    private fun createWaterData(level: Int): BlockData = Material.WATER.createBlockData().apply {
        (this as? Levelled)?.level = level
    }

    private fun createTextDisplay(world: World, location: Location, waterLevel: Int, duration: Long): TextDisplay {
        return world.spawn(location.add(0.5, 0.5, 0.5), TextDisplay::class.java) { display ->
            display.text(createDisplayText(location, waterLevel, duration))
            display.isSeeThrough = true
            display.viewRange = 64.0f
            display.isVisibleByDefault = false
            display.billboard = Display.Billboard.CENTER
            display.isDefaultBackground = false
            display.backgroundColor = Color.fromARGB(0, 0, 0, 0)
            display.textOpacity = 180.toByte()
        }
    }

    private fun createDisplayText(location: Location, waterLevel: Int, duration: Long) =
        Component.text("${location.blockX}, ${location.blockY}, ${location.blockZ}", NamedTextColor.WHITE)
            .append(Component.newline())
            .append(Component.text("L: $waterLevel | D: ", NamedTextColor.AQUA))
            .append(Component.text("${duration / 20}s", NamedTextColor.YELLOW))

    private fun startTimerUpdateTask(player: Player, textDisplay: TextDisplay, duration: Long, location: Location, waterLevel: Int) {
        var remainingTicks = duration
        server.scheduler.runTaskTimer(this, Runnable {
            if (remainingTicks <= 0 || !textDisplay.isValid) return@Runnable
            textDisplay.text(createDisplayText(location, waterLevel, remainingTicks))
            player.showEntity(this, textDisplay)
            remainingTicks--
        }, 0L, 1L)
    }

    private fun schedulePuddleRemoval(player: Player, puddleLocation: IntLocation, textDisplay: TextDisplay, duration: Long): BukkitTask {
        return server.scheduler.runTaskLater(this, Runnable {
            player.sendBlockChange(puddleLocation.toBukkitLocation(), Material.AIR.createBlockData())
            textDisplay.remove()
            puddles[player.uniqueId]?.remove(puddleLocation)
        }, duration)
    }

    @EventHandler
    fun onPlayerMove(event: PlayerMoveEvent) {
        val player = event.player
        val playerLocation = IntLocation(
            player.world,
            player.location.blockX,
            player.location.blockY,
            player.location.blockZ
        )
        val isInPuddle = puddles[player.uniqueId]?.keys?.any { it == playerLocation } == true
        ComponentLogger.logger().info("Player ${player.name} moved to $playerLocation. In puddle: $isInPuddle")
        if (isInPuddle) applySpeedBoost(player) else removeSpeedBoostCompletely(player)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        puddles.remove(player.uniqueId)?.values?.forEach(BukkitTask::cancel)
    }

    private fun applySpeedBoost(player: Player) {
        val attribute = player.getAttribute(Attribute.GENERIC_WATER_MOVEMENT_EFFICIENCY) ?: return
        val modifier = AttributeModifier(modifierKey, 10.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1)

        if (attribute.modifiers.none { it.name == modifierKey.key }) {
            attribute.addModifier(modifier)
        } else {
            attribute.modifiers.find { it.name == modifierKey.key }?.let {
                attribute.removeModifier(it)
                attribute.addModifier(modifier)
            }
        }
    }

    private fun removeSpeedBoostCompletely(player: Player) {
        player.getAttribute(Attribute.GENERIC_WATER_MOVEMENT_EFFICIENCY)?.let { attr ->
            attr.modifiers.find { it.name == modifierKey.key }?.let { attr.removeModifier(it) }
        }
    }

    private fun isPuddleableBlock(material: Material): Boolean {
        return material.isSolid && material.isOccluding && material.isCollidable &&
                material !in setOf(Material.WATER, Material.LAVA, Material.BUBBLE_COLUMN, Material.KELP,
            Material.KELP_PLANT, Material.SEAGRASS, Material.TALL_SEAGRASS, Material.WATER_CAULDRON)
    }

    data class IntLocation(val world: World, val x: Int, val y: Int, val z: Int) {
        fun toBukkitLocation(): Location = Location(world, x.toDouble(), y.toDouble(), z.toDouble())

        override fun toString(): String = "IntLocation{world=${world.name},x=$x,y=$y,z=$z}"
    }

    private fun stopWeatherTask(world: World) {
        weatherTasks.remove(world)?.cancel()
    }
}