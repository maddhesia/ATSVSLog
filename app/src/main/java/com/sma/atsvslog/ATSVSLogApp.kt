package com.sma.atsvslog

import android.app.Application

class ATSVSLogApp : Application() {

    override fun onCreate() {
        super.onCreate()

        println("ATSVSLog Application Started")
    }
}