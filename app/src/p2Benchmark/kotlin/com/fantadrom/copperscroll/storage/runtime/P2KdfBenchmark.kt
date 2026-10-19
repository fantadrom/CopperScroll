package com.fantadrom.copperscroll.storage.runtime

import android.app.ActivityManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fantadrom.copperscroll.storage.access.CredentialManager
import com.fantadrom.copperscroll.storage.access.SecretEncoding
import com.fantadrom.copperscroll.storage.crypto.*
import com.fantadrom.copperscroll.storage.format.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Compiled ONLY with p2KdfBenchmark=true; a fresh process per profile/slot count. */
@RunWith(AndroidJUnit4::class)
class P2KdfBenchmark {
    @Test fun measureSelectedProfile() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        val memory=args.getString("p2BenchmarkMemoryKiB")!!.toInt()
        val iterations=args.getString("p2BenchmarkIterations")!!.toInt()
        val slots=args.getString("p2BenchmarkSlots")!!.toInt()
        val repeats=args.getString("p2BenchmarkRepeats")!!.toInt()
        KdfParameters(memory,iterations).validate(); check(slots in 1..2 && repeats in 0..100)
        P2CryptoRuntimeTest().requireProductionTarget()
        val container=Bytes(ByteArray(16) { 1 })
        // Invalid synthetic envelope tags deliberately exercise one/two sequential real derivations.
        // Creating valid envelopes here would warm Argon2 BEFORE the cold measurement.
        val descriptors=(1..slots).map { role ->
            val zone=Bytes(ByteArray(16) { (role+1).toByte() })
            Descriptor(zone,role,1,1,memory,iterations,Bytes(ByteArray(16) { 3 }),Bytes(ByteArray(12) { 4 }),
                Bytes(ByteArray(48)),"",LogicalRef(Bytes(ByteArray(32) { 5 }),Bytes(ByteArray(32)),1,zone,0),
                Bytes(ByteArray(32)),PhysicalRef(131072,16384,Bytes(ByteArray(32))))
        }
        val power=instrumentation.targetContext.getSystemService(PowerManager::class.java)
        val activity=instrumentation.targetContext.getSystemService(ActivityManager::class.java)
        val memoryInfo=ActivityManager.MemoryInfo().also { activity.getMemoryInfo(it) }
        val manager=CredentialManager()
        val results=ArrayList<String>()
        val warm=ArrayList<Long>(); var cold=0L
        val prefix="api=${Build.VERSION.SDK_INT}\tmodel=${Build.MODEL}\tabi=${Build.SUPPORTED_ABIS.joinToString(",")}\t"+
            "memoryKiB=$memory\titerations=$iterations\tparallelism=1\tslots=$slots\trepeats=$repeats\t"+
            "lowRam=${activity.isLowRamDevice}\tavailableBytes=${memoryInfo.availMem}\tlowMemory=${memoryInfo.lowMemory}\t"+
            "powerSave=${power.isPowerSaveMode}\tinteractive=${power.isInteractive}"
        results.add(prefix)
        results.add("phase\tsample\telapsedNs\tpssKiBBefore\tpssKiBAfter\tjavaHeapBefore\tjavaHeapAfter\tvmHwmKiBAfter\tthermalBefore\tthermalAfter\tresult")
        fun pss()=Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
        fun heap()=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()
        fun highWater(): String = try {
            File("/proc/self/status").useLines { lines -> lines.firstOrNull { it.startsWith("VmHWM:") } }
                ?.substringAfter(':')?.trim()?.substringBefore(' ') ?: "unavailable"
        } catch (_: Exception) { "unavailable" }
        SecretEncoding.pin4("0012").use { secret ->
            for(sample in 0..repeats) {
                val beforePss=pss(); val beforeHeap=heap(); val beforeThermal=power.currentThermalStatus
                val start=System.nanoTime(); var result="CredentialMismatch"
                try { manager.login(container,descriptors,secret).close(); error("Synthetic invalid tag unexpectedly passed") }
                catch(e: CryptoException) {
                    check(e.failure==CryptoFailure.CredentialMismatch || e.failure==CryptoFailure.ResourceLimit)
                    result=e.failure.name
                }
                val elapsed=System.nanoTime()-start
                if(sample==0) cold=elapsed else if(result=="CredentialMismatch") warm.add(elapsed)
                results.add("${if(sample==0) "cold-process" else "warm"}\t$sample\t$elapsed\t$beforePss\t${pss()}\t$beforeHeap\t${heap()}\t${highWater()}\t$beforeThermal\t${power.currentThermalStatus}\t$result")
                if(result=="ResourceLimit") break // Explicit measurement, no automatic KDF downgrade/retry.
            }
        }
        warm.sort()
        if(warm.isNotEmpty()) results.add("summary\tcoldNs=$cold\twarmSamples=${warm.size}\t"+
            "warmP50Ns=${warm[(kotlin.math.ceil(warm.size*0.50).toInt()-1).coerceAtLeast(0)]}\t"+
            "warmP95Ns=${warm[(kotlin.math.ceil(warm.size*0.95).toInt()-1).coerceAtLeast(0)]}")
        val text=results.joinToString("\n",postfix="\n")
        // Only synthetic timing/environment metrics. Logcat is collected by the connected runner.
        for(line in results) Log.i("P2KdfBenchmark",line)
        instrumentation.sendStatus(0,Bundle().apply { putString("stream","\nP2_KDF_METRICS\n$text") })
        args.getString("additionalTestOutputDir")?.let { output ->
            File(output).mkdirs(); File(output,"p2-kdf.tsv").writeText(text)
        }
    }
}
