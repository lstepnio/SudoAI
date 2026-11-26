package com.majjix.sudoai

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

fun scheduleUploads(context: Context) {
    val job =
        JobInfo.Builder(71, ComponentName(context, UploadJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPeriodic(15 * 60 * 1000L)
            .build()
    context.getSystemService(JobScheduler::class.java).schedule(job)
}

class UploadJobService : JobService() {
    @Volatile private var cancelled = false

    override fun onStartJob(params: JobParameters): Boolean {
        cancelled = false
        Store.get(this).executor.execute {
            val result = runCatching { Store.get(this).sync() }
            if (!cancelled) jobFinished(params, result.isFailure)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        cancelled = true
        return true
    }
}
