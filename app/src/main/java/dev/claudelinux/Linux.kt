package dev.claudelinux

import android.content.Context
import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Everything about the proot + Alpine guest: paths, install, launch command line. */
object Linux {
    private const val ALPINE_BASE = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/"

    fun rootfs(c: Context) = File(c.filesDir, "rootfs")
    private fun lib(c: Context, n: String) = File(c.applicationInfo.nativeLibraryDir, n)
    fun prootPath(c: Context): String = lib(c, "libproot.so").absolutePath
    fun extracted(c: Context) = File(rootfs(c), ".extracted").exists()
    fun ready(c: Context) = File(rootfs(c), "root/.bootstrapped").exists()

    /** argv for proot (argv[0] included). `guest` is the command run inside the Alpine rootfs. */
    fun argv(c: Context, guest: List<String>): Array<String> {
        val fake = File(c.filesDir, "fakeproc")
        val l = mutableListOf(
            "proot", "--kill-on-exit", "--link2symlink", "-0",
            "-r", rootfs(c).absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys"
        )
        // Android hides these from apps; Node/others read them.
        for (n in listOf("stat", "loadavg", "uptime", "version")) l += listOf("-b", "${File(fake, n).absolutePath}:/proc/$n")
        l += listOf("-w", "/root")
        l += guest
        return l.toTypedArray()
    }

    fun env(c: Context): Array<String> {
        val tmp = File(c.filesDir, "tmp").apply { mkdirs() }
        val e = mutableListOf(
            "PROOT_LOADER=${lib(c, "libproot_loader.so").absolutePath}",
            "PROOT_TMP_DIR=${tmp.absolutePath}",
            "HOME=/root", "USER=root", "LOGNAME=root", "SHELL=/bin/bash",
            "TERM=xterm-256color", "COLORTERM=truecolor", "LANG=C.UTF-8", "TMPDIR=/tmp",
            "PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "USE_BUILTIN_RIPGREP=0", "DISABLE_AUTOUPDATER=1"
        )
        System.getenv("ANDROID_ROOT")?.let { e += "ANDROID_ROOT=$it" }
        System.getenv("ANDROID_DATA")?.let { e += "ANDROID_DATA=$it" }
        e += Config.envVars(c)
        return e.toTypedArray()
    }

    /** Idempotent: files proot needs before every launch. */
    fun prepare(c: Context) {
        File(c.filesDir, "tmp").mkdirs()
        val fake = File(c.filesDir, "fakeproc").apply { mkdirs() }
        fun w(n: String, s: String) { val f = File(fake, n); if (!f.exists()) f.writeText(s) }
        w("stat", "cpu  1957 0 2877 93280 262 342 254 87 0 0\ncpu0 1957 0 2877 93280 262 342 254 87 0 0\nintr 0\nctxt 0\nbtime 0\nprocesses 0\nprocs_running 1\nprocs_blocked 0\n")
        w("loadavg", "0.12 0.07 0.02 2/165 765\n")
        w("uptime", "124.08 932.80\n")
        w("version", "Linux version 6.1.0 (proot@android) (gcc 12.2.0) #1 SMP PREEMPT\n")
        val root = rootfs(c)
        File(root, "root").mkdirs()
        File(root, "etc/resolv.conf").apply { parentFile?.mkdirs() }.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        c.assets.open("bootstrap.sh").use { i -> File(root, "root/bootstrap.sh").outputStream().use { o -> i.copyTo(o) } }
    }

    /** Download + verify + unpack the latest Alpine aarch64 minirootfs. Blocking: call off the UI thread. */
    fun install(c: Context, progress: (String, Int) -> Unit) {
        val root = rootfs(c)
        if (root.exists()) root.deleteRecursively()
        root.mkdirs()
        progress("Looking up latest Alpine…", -1)
        val yaml = httpText(ALPINE_BASE + "latest-releases.yaml")
        var file: String? = null
        var sha: String? = null
        for (line in yaml.lines()) {
            val t = line.trim()
            if (file == null && t.startsWith("file: alpine-minirootfs-")) file = t.removePrefix("file:").trim()
            else if (file != null && sha == null && t.startsWith("sha256:")) sha = t.removePrefix("sha256:").trim()
        }
        val f = file ?: throw IOException("Could not find the Alpine minirootfs in the release list")
        val s = sha ?: throw IOException("Could not find the Alpine SHA-256 in the release list")
        val tgz = File(c.cacheDir, f)
        download(ALPINE_BASE + f, tgz, s) { pct -> progress("Downloading $f", pct) }
        progress("Unpacking…", -1)
        extract(tgz, root)
        tgz.delete()
        prepare(c)
        File(root, ".extracted").writeText(f)
    }

    private fun httpText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000; conn.readTimeout = 30000
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun download(url: String, dest: File, sha256: String, cb: (Int) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000; conn.readTimeout = 30000
        val total = conn.contentLengthLong
        val md = MessageDigest.getInstance("SHA-256")
        conn.inputStream.use { ins ->
            dest.outputStream().use { out ->
                val buf = ByteArray(65536)
                var done = 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n); md.update(buf, 0, n); done += n
                    if (total > 0) cb((done * 100 / total).toInt())
                }
            }
        }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        if (!got.equals(sha256, ignoreCase = true)) { dest.delete(); throw IOException("SHA-256 mismatch for download") }
    }

    private fun chmod(f: File, mode: Int) { try { Os.chmod(f.path, mode) } catch (_: Exception) {} }

    private fun extract(tgz: File, root: File) {
        val rootPath = root.toPath().normalize()
        GZIPInputStream(BufferedInputStream(FileInputStream(tgz))).use { gz ->
            val tar = TarReader(gz)
            var e = tar.next()
            while (e != null) {
                val name = e.name.removePrefix("./").trimEnd('/')
                val dest = File(root, name)
                if (name.isNotEmpty() && dest.toPath().normalize().startsWith(rootPath)) {
                    val mode = e.mode and 0xFFF
                    when {
                        e.isDirectory -> { dest.mkdirs(); chmod(dest, mode or 0x1C0) }
                        e.isSymbolicLink -> {
                            dest.parentFile?.mkdirs(); dest.delete()
                            try { Os.symlink(e.linkName, dest.path) } catch (_: Exception) {}
                        }
                        e.isLink -> {
                            val src = File(root, e.linkName.removePrefix("./"))
                            dest.parentFile?.mkdirs()
                            if (src.exists()) src.copyTo(dest, overwrite = true)
                        }
                        e.isFile -> {
                            dest.parentFile?.mkdirs()
                            FileOutputStream(dest).use { out -> tar.copyTo(out) }
                            chmod(dest, mode or 0x180)
                        }
                        else -> { /* device nodes, fifos: proot binds /dev instead */ }
                    }
                }
                e = tar.next()
            }
        }
    }
}
