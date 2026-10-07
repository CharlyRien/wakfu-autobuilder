package me.chosante.bdataextractor

import me.chosante.common.Monster
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.imageio.ImageIO

internal data class PortraitResponse(
    val status: Int,
    val bytes: ByteArray = byteArrayOf(),
)

internal data class PortraitReport(
    val fetched: Int,
    val changed: Int,
    val bossesWithoutPortrait: Int,
    val removedOrphans: Int,
    val removedUnavailable: Int,
)

/** Network-only source: Ankama's unversioned portal portraits, not the client's narrow monster banners. */
internal fun downloadMonsterPortraits(
    monsters: List<Monster>,
    destination: File,
): PortraitReport =
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().use { client ->
        fetchMonsterPortraits(monsters, destination) { gfx ->
            val url = "https://static.ankama.com/wakfu/portal/game/monster/200/$gfx.png"
            val response =
                client.send(
                    HttpRequest
                        .newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "WakfuAutobuilder/monster-portraits (+https://github.com/CharlyRien/wakfu-autobuilder)")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofByteArray()
                )
            Thread.sleep(50) // Sequential, maintainer-only fetch; be polite to the portal host.
            println("  portrait $gfx: HTTP ${response.statusCode()}")
            PortraitResponse(response.statusCode(), response.body())
        }
    }

/** Stage and validate every response before touching committed assets; unexpected errors fail loudly. */
internal fun fetchMonsterPortraits(
    monsters: List<Monster>,
    destination: File,
    fetch: (Int) -> PortraitResponse,
): PortraitReport {
    val bosses = monsters.filter { it.isBoss }
    require(bosses.isNotEmpty()) { "No bosses in monsters.json; refusing to prune portraits" }
    val gfxIds = bosses.mapNotNull { it.gfx }.toSortedSet()
    val portraits = linkedMapOf<Int, ByteArray>()
    for (gfx in gfxIds) {
        val response = fetch(gfx)
        when (response.status) {
            403, 404 -> continue // Official host has no portrait for this boss.
            200 -> {
                val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
                require(
                    response.bytes
                        .take(8)
                        .toByteArray()
                        .contentEquals(signature)
                ) { "Portrait $gfx is not PNG" }
                val image = ImageIO.read(ByteArrayInputStream(response.bytes))
                require(image != null && image.width == 200 && image.height == 200) { "Portrait $gfx is not 200x200" }
                portraits[gfx] = response.bytes
            }
            else -> error("Portrait $gfx: unexpected HTTP ${response.status}")
        }
    }
    destination.mkdirs()
    var changed = 0
    for ((gfx, bytes) in portraits) {
        val file = File(destination, "$gfx.png")
        if (!file.isFile || !file.readBytes().contentEquals(bytes)) {
            file.writeBytes(bytes)
            changed++
        }
    }
    var orphans = 0
    var unavailable = 0
    for (file in destination.listFiles().orEmpty().filter { it.extension == "png" }) {
        val gfx = file.nameWithoutExtension.toIntOrNull()
        if (gfx !in portraits) {
            check(file.delete()) { "Could not remove stale portrait $file" }
            if (gfx in gfxIds) unavailable++ else orphans++
        }
    }
    return PortraitReport(portraits.size, changed, bosses.count { it.gfx !in portraits }, orphans, unavailable)
}
