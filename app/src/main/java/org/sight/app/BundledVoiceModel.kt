package org.sight.app

import android.content.Context
import org.vosk.Model
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream

/** The model is packaged in the APK; microphone audio never leaves the device. */
object BundledVoiceModel {
    const val ASSET="voice-model-small-en-us-0.15.zip"
    const val SHA256="30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
    private const val PREFIX="vosk-model-small-en-us-0.15/"
    private val sizes=mapOf("am/final.mdl" to 15_962_575L,"graph/disambig_tid.int" to 102L,
        "graph/HCLr.fst" to 22_416_994L,"graph/Gr.fst" to 24_013_795L,"graph/phones/word_boundary.int" to 1_761L,
        "conf/model.conf" to 290L,"conf/mfcc.conf" to 131L,"ivector/splice.conf" to 35L,
        "ivector/final.dubm" to 168_048L,"ivector/global_cmvn.stats" to 1_080L,"ivector/final.ie" to 8_288_887L,
        "ivector/online_cmvn.conf" to 95L,"ivector/final.mat" to 44_975L,"README" to 199L)

    @Synchronized fun open(context:Context,cancelled:()->Boolean={false}):Model {
        val root=File(context.noBackupFilesDir,"offline-voice-${SHA256.take(12)}")
        val complete=File(root,"verified.sha256")
        fun checkCancellation(){ if(cancelled()) throw CancellationException("Voice request cancelled") }
        checkCancellation()
        val ready=complete.isFile && complete.readText()==SHA256 && sizes.all { (path,size)->File(root,path).let { it.isFile && it.length()==size } }
        if(!ready) {
            // Only this fixed app-private directory is replaced; no user documents are involved.
            check(root.canonicalFile.parentFile==context.noBackupFilesDir.canonicalFile)
            if(root.exists()) check(root.deleteRecursively()) { "Could not replace incomplete offline voice model" }
            check(root.mkdirs()) { "Could not create app-private voice model directory" }
            val digest=MessageDigest.getInstance("SHA-256")
            context.assets.open(ASSET).use { stream ->
                val buffer=ByteArray(65_536)
                while(true){ checkCancellation(); val count=stream.read(buffer); if(count<0)break; digest.update(buffer,0,count) }
            }
            check(digest.digest().joinToString(""){ "%02x".format(it) }==SHA256) { "Bundled voice model checksum mismatch" }
            val extracted=mutableSetOf<String>()
            context.assets.open(ASSET).use { raw -> ZipInputStream(raw).use { zip ->
                val buffer=ByteArray(65_536)
                while(true) {
                    checkCancellation()
                    val entry=zip.nextEntry ?: break
                    check(entry.name.startsWith(PREFIX)) { "Unexpected voice model archive path" }
                    val relative=entry.name.removePrefix(PREFIX)
                    if(entry.isDirectory){ zip.closeEntry(); continue }
                    val expected=sizes[relative] ?: error("Unexpected voice model file")
                    check(extracted.add(relative)) { "Duplicate voice model file" }
                    val file=File(root,relative)
                    check(file.canonicalPath.startsWith(root.canonicalPath+File.separator)) { "Unsafe voice model archive path" }
                    check(file.parentFile?.mkdirs()==true || file.parentFile?.isDirectory==true)
                    var written=0L
                    file.outputStream().use { output -> while(true) {
                        checkCancellation(); val count=zip.read(buffer); if(count<0)break
                        written+=count; check(written<=expected) { "Voice model file exceeds expected size" }; output.write(buffer,0,count)
                    } }
                    check(written==expected) { "Truncated voice model file" }
                    zip.closeEntry()
                }
            } }
            check(extracted==sizes.keys) { "Incomplete voice model archive" }
            complete.writeText(SHA256)
        }
        checkCancellation()
        return Model(root.absolutePath)
    }
}
