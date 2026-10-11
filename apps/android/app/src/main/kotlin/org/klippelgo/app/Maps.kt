package org.klippelgo.app

import android.content.Context
import com.fasterxml.jackson.databind.ObjectMapper
import com.valhalla.valhalla.Valhalla
import com.valhalla.valhalla.config.ValhallaConfigFactory
import org.klippelgo.chain.ChainException
import org.klippelgo.chain.KlippelGoClient
import org.klippelgo.core.Coordinate
import org.klippelgo.core.MapPack
import org.klippelgo.core.MeasuredRoute
import org.klippelgo.core.VehicleKind
import org.klippelgo.core.attributesRequest
import org.klippelgo.core.routeRequest
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Where the map pack for this phone's region stands. */
sealed interface MapState {
    /** Asking the chain which pack is current and looking for it on this phone. */
    data object Checking : MapState

    /** Governance has not activated a pack for the region on this chain. */
    data object NotPublished : MapState

    /** The current pack is not on this phone yet. `bytes` is its size, 0 when the mirror could not be asked. */
    data class NotDownloaded(val bytes: Long) : MapState

    data class Downloading(val done: Long, val total: Long) : MapState

    data class Ready(val region: String, val version: Int, val basemap: String, val centre: Coordinate) : MapState

    data class Failed(val message: String) : MapState
}

/** A map pack that passed every check, with the router that runs on its tiles. */
class OpenPack(val pack: MapPack, private val valhalla: Valhalla) {
    /**
     * The fastest route between two points, searched by Valhalla on this phone and measured by the verifier on
     * the pack's proof index. Throws when there is no route or the verifier does not accept the one found.
     */
    fun route(from: Coordinate, to: Coordinate, vehicle: VehicleKind): MeasuredRoute {
        val route = valhalla.routeRaw(routeRequest(from, to, vehicle))
        val attributes = valhalla.traceAttributesRaw(attributesRequest(route, vehicle))
        return pack.measureRoute(route, attributes, vehicle)
    }

    fun close() = valhalla.close()
}

/**
 * Keeps map packs on this phone. A pack is fetched from a mirror by its id and nothing the mirror sends is
 * trusted: the manifest must hash to the id governance activated in MapRegistry, every file must hash to its
 * entry in the manifest, and the proof index must hash to the root MapRegistry holds. The mirror learns which
 * region this phone asked for, and nothing about where in it anyone goes.
 */
class MapStore(private val context: Context) {
    private val root = File(context.filesDir, "packs")

    private fun dir(packId: ByteArray) = File(root, Numeric.toHexStringNoPrefix(packId))

    private fun complete(packId: ByteArray) = File(dir(packId), COMPLETE)

    fun isInstalled(packId: ByteArray): Boolean = complete(packId).exists()

    /** Size of the pack's files according to its manifest on the mirror, after checking the manifest is the pack's. */
    fun size(mirror: String, packId: ByteArray): Long = files(manifest(mirror, packId)).sumOf { it.bytes }

    /** Downloads the pack and checks every file. Files already fetched and intact are kept, so it can resume. */
    fun download(mirror: String, packId: ByteArray, progress: (done: Long, total: Long) -> Unit) {
        val manifest = manifest(mirror, packId)
        val files = files(manifest)
        val total = files.sumOf { it.bytes }
        val dir = dir(packId).apply { mkdirs() }
        var done = 0L
        progress(0, total)
        for (file in files) {
            val target = File(dir, file.name)
            if (!target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
                throw ChainException("The map pack lists a file outside itself")
            }
            if (!(target.isFile && target.length() == file.bytes && sha256(target).contentEquals(file.sha256))) {
                target.parentFile?.mkdirs()
                val part = File(target.path + ".part")
                val digest = MessageDigest.getInstance("SHA-256")
                open(mirror, packId, file.name).use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 16)
                        var fetched = 0L
                        var reported = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                            fetched += n
                            if (fetched - reported > 1 shl 20) {
                                reported = fetched
                                progress(done + fetched, total)
                            }
                        }
                    }
                }
                if (!digest.digest().contentEquals(file.sha256)) {
                    part.delete()
                    throw ChainException("${file.name} from the mirror does not match the map pack's manifest")
                }
                if (!part.renameTo(target)) throw ChainException("Could not store ${file.name}")
            }
            done += file.bytes
            progress(done, total)
        }
        File(dir, MANIFEST).writeBytes(manifest)
        complete(packId).writeText("")
        // Older packs of the region are no use once a newer one is in place.
        root.listFiles()?.filter { it.isDirectory && it != dir }?.forEach { it.deleteRecursively() }
    }

    /** Opens an installed pack, checks it against MapRegistry and starts the router on its tiles. */
    fun open(packId: ByteArray, client: KlippelGoClient): OpenPack {
        val pack = MapPack.open(dir(packId).path)
        if (!pack.id().contentEquals(packId)) throw ChainException("The map pack on this phone is not the one requested")
        val root = client.packProofRoot(packId) ?: throw ChainException("MapRegistry does not know this map pack")
        if (!pack.proofRoot().contentEquals(root)) throw ChainException("The map pack's proof root is not the one in MapRegistry")
        val valhalla = Valhalla(context, ValhallaConfigFactory.usingTileExtract(pack.routingPath()))
        return OpenPack(pack, valhalla)
    }

    private class Entry(val name: String, val sha256: ByteArray, val bytes: Long)

    /** The manifest from the mirror, accepted only if it hashes to the pack id. */
    private fun manifest(mirror: String, packId: ByteArray): ByteArray {
        val bytes = open(mirror, packId, MANIFEST).use { it.readBytes() }
        if (!Hash.sha3(bytes).contentEquals(packId)) throw ChainException("The mirror's manifest is not this map pack's")
        return bytes
    }

    private fun files(manifest: ByteArray): List<Entry> =
        ObjectMapper().readTree(manifest).get("files").fields().asSequence().map { (name, entry) ->
            Entry(name, Numeric.hexStringToByteArray(entry.get("sha256").asText()), entry.get("bytes").asLong())
        }.toList()

    private fun open(mirror: String, packId: ByteArray, name: String) =
        (URL("${mirror.trimEnd('/')}/${Numeric.toHexString(packId)}/$name").openConnection() as HttpURLConnection).run {
            connectTimeout = 5_000
            readTimeout = 30_000
            if (responseCode != 200) throw ChainException("The map mirror answered $responseCode for $name")
            inputStream
        }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest()
    }

    companion object {
        /** Cascavel, Paraná, the pilot city. Every phone uses its pack until the app picks one from where it is. */
        const val PILOT_REGION = "br-pr-cascavel"

        /** Port of the pack mirror next to the chain node (`make serve-packs`). */
        const val MIRROR_PORT = 4002

        private const val MANIFEST = "manifest.json"
        private const val COMPLETE = ".complete"
    }
}
