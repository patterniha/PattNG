package com.v2ray.ang.ui.main

import androidx.annotation.DrawableRes
import com.v2ray.ang.R

/**
 * Flag pictures for the countries an exit is most often in (flag-icons, MIT, see assets/licenses/FLAG_ICONS_MIT.txt).
 * Iran has its own picture, and a country without one here is shown by its emoji flag.
 */
@DrawableRes
internal fun flagDrawable(countryCode: String?): Int? = when (countryCode?.uppercase()) {
    "AE" -> R.drawable.flag_ae
    "AL" -> R.drawable.flag_al
    "AM" -> R.drawable.flag_am
    "AR" -> R.drawable.flag_ar
    "AT" -> R.drawable.flag_at
    "AU" -> R.drawable.flag_au
    "AZ" -> R.drawable.flag_az
    "BA" -> R.drawable.flag_ba
    "BD" -> R.drawable.flag_bd
    "BE" -> R.drawable.flag_be
    "BG" -> R.drawable.flag_bg
    "BR" -> R.drawable.flag_br
    "BY" -> R.drawable.flag_by
    "CA" -> R.drawable.flag_ca
    "CH" -> R.drawable.flag_ch
    "CL" -> R.drawable.flag_cl
    "CO" -> R.drawable.flag_co
    "CY" -> R.drawable.flag_cy
    "CZ" -> R.drawable.flag_cz
    "DE" -> R.drawable.flag_de
    "DK" -> R.drawable.flag_dk
    "EE" -> R.drawable.flag_ee
    "EG" -> R.drawable.flag_eg
    "ES" -> R.drawable.flag_es
    "FI" -> R.drawable.flag_fi
    "FR" -> R.drawable.flag_fr
    "GB" -> R.drawable.flag_gb
    "GE" -> R.drawable.flag_ge
    "GR" -> R.drawable.flag_gr
    "HK" -> R.drawable.flag_hk
    "HR" -> R.drawable.flag_hr
    "HU" -> R.drawable.flag_hu
    "ID" -> R.drawable.flag_id
    "IE" -> R.drawable.flag_ie
    "IL" -> R.drawable.flag_il
    "IN" -> R.drawable.flag_in
    "IS" -> R.drawable.flag_is
    "IT" -> R.drawable.flag_it
    "JP" -> R.drawable.flag_jp
    "KE" -> R.drawable.flag_ke
    "KR" -> R.drawable.flag_kr
    "KW" -> R.drawable.flag_kw
    "KZ" -> R.drawable.flag_kz
    "LK" -> R.drawable.flag_lk
    "LT" -> R.drawable.flag_lt
    "LU" -> R.drawable.flag_lu
    "LV" -> R.drawable.flag_lv
    "MD" -> R.drawable.flag_md
    "ME" -> R.drawable.flag_me
    "MK" -> R.drawable.flag_mk
    "MT" -> R.drawable.flag_mt
    "MX" -> R.drawable.flag_mx
    "MY" -> R.drawable.flag_my
    "NG" -> R.drawable.flag_ng
    "NL" -> R.drawable.flag_nl
    "NO" -> R.drawable.flag_no
    "NZ" -> R.drawable.flag_nz
    "PH" -> R.drawable.flag_ph
    "PK" -> R.drawable.flag_pk
    "PL" -> R.drawable.flag_pl
    "PT" -> R.drawable.flag_pt
    "QA" -> R.drawable.flag_qa
    "RO" -> R.drawable.flag_ro
    "RS" -> R.drawable.flag_rs
    "RU" -> R.drawable.flag_ru
    "SA" -> R.drawable.flag_sa
    "SE" -> R.drawable.flag_se
    "SG" -> R.drawable.flag_sg
    "SI" -> R.drawable.flag_si
    "SK" -> R.drawable.flag_sk
    "TH" -> R.drawable.flag_th
    "TR" -> R.drawable.flag_tr
    "TW" -> R.drawable.flag_tw
    "UA" -> R.drawable.flag_ua
    "US" -> R.drawable.flag_us
    "VN" -> R.drawable.flag_vn
    "ZA" -> R.drawable.flag_za
    else -> null
}
