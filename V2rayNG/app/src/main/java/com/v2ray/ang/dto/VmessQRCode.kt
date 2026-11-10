package com.v2ray.ang.dto

data class VmessQRCode(
    var v: String = "",
    var ps: String = "",
    var add: String = "",
    var port: String = "",
    var id: String = "",
    var aid: String = "0",
    var scy: String = "",
    var net: String = "",
    var type: String = "",
    var host: String = "",
    var path: String = "",
    var tls: String = "",
    var sni: String = "",
    var alpn: String = "",
    var fp: String = "",
    var cs: String = "",
    // PattNG: curvePreferences as the profile holds them, and enableSessionResumption, "1" when on
    var cp: String = "",
    var esr: String = "",
    var insecure: String = "",
    var vcn: String = "",
    var pcs: String = "",
    var dialMode: String = ""
)