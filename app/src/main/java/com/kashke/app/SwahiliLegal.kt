package com.kashke.app

internal fun swahiliLegalContent(page: String): String = if (page == "Privacy Policy") """
    Sera ya Faragha ya Kash.ke
    Ilisasishwa tarehe 6 Oktoba 2026

    Kash.ke husaidia wanachama kuungana, kuomba msaada wa kifedha, kukubaliana kuhusu masharti na kufuatilia malipo.

    Taarifa tunazotumia
    Tunachakata vitambulisho vya akaunti, wasifu, miunganisho, maombi ya msaada, ofa, makubaliano, malipo, rekodi za pochi, mapendeleo ya programu na vitambulisho vya kifaa kwa arifa. Mtoa huduma wa uthibitishaji hushughulikia nywila. Fomu za mawasiliano hukusanya jina, barua pepe ya kujibu, mada na ujumbe wako ili kushughulikia swali lako. Ujumbe huhifadhiwa kwa usalama na kutumwa kwa faragha kwenye kisanduku chetu cha msaada kupitia mtoa huduma wa barua pepe. Ukiruhusu anwani, nambari za simu hutumiwa kutafuta wanachama unaowajua. Ruhusa ya eneo la kukadiria inahitajika kuingia kwenye programu; toleo hili halitumi wala kuhifadhi viwianishi vya eneo la kifaa. Android hufanya ukaguzi wa biometriki; Kash.ke haipokei alama ya kidole wala taarifa za biometriki.

    Matumizi na kushiriki taarifa
    Tunatumia taarifa kuendesha akaunti, kulinganisha anwani, kuonyesha wasifu, kusimamia makubaliano na malipo, kutuma arifa na kulinda huduma. Wanachama wengine wanaweza kuona wasifu na maelezo ya mikopo yanayoonyeshwa kwenye programu. Washiriki wa makubaliano wanaweza kuona rekodi zinazowahusu. Watoa huduma wa uthibitishaji, uhifadhi, arifa na malipo huchakata taarifa zinazohitajika kwa huduma zao. Hatuuzi taarifa binafsi.

    Chaguo zako
    Unaweza kuhariri wasifu, kunyamazisha maombi, kuondoa miunganisho, kubadilisha nenosiri, kuchagua lugha na kuzima biometriki. Android hudhibiti ruhusa za anwani, arifa na eneo. Kukataa ruhusa ya eneo kunazuia ufikiaji wa programu. Kuondoa muunganisho au kunyamazisha hakufuti makubaliano yaliyopo.

    Uhifadhi na ulinzi
    Tunahifadhi taarifa zinazohitajika kutoa huduma, kutatua migogoro na kutimiza wajibu wa kutunza rekodi. Maombi ya kufuta akaunti, kupata au kusahihisha taarifa yatumwe kwa support@kash.ke. Hakuna mfumo unaohakikisha usalama kamili. Usishiriki nenosiri au taarifa za kufungua akaunti.

    Watoto na mabadiliko
    Huduma ni kwa watu wenye miaka 18 au zaidi. Tunaweza kusasisha sera na tutawasilisha mabadiliko makubwa.
""".trimIndent() else """
    Sheria na Masharti ya Kash.ke
    Ilisasishwa tarehe 6 Oktoba 2026

    Kutumia Kash.ke
    Kash.ke huwawezesha wanachama kuungana, kuomba na kutoa msaada wa kifedha, kukagua masharti na kufuatilia makubaliano na malipo. Lazima uwe na miaka 18 au zaidi, utoe taarifa sahihi, ulinde akaunti yako na utumie huduma kwa njia halali.

    Makubaliano ya msaada
    Kagua kiasi, riba, jumla ya malipo, muda wa kulipa na taarifa kuhusu kuzidi kikomo kabla ya kukubali. Kubali masharti unayoelewa na unayoweza kutimiza tu. Wajibu wa kufadhili na kulipa unafuata masharti yaliyokubaliwa. Kash.ke haihakikishi ufadhili, malipo, mwenendo wa mwanachama mwingine au usahihi wa wasifu na viashiria vya uaminifu.

    Malipo na rekodi
    Hakikisha maelezo na hali ya malipo kabla ya kurudia muamala. Ada za mtoa huduma na ucheleweshaji vinaweza kutumika ikiwa vimeelezwa. Ripoti tofauti mapema. Usichukulie malipo ambayo hayajathibitishwa kuwa yamekamilika.

    Udhibiti wa wanachama
    Kunyamazisha huficha maombi ya mwanachama na kuzuia arifa mpya za maombi. Kuondoa muunganisho humaliza muunganisho huo. Hatua hizi hazisamehe deni, kufuta makubaliano au kuondoa wajibu wa kulipa. Ruhusa ya eneo inahitajika kufikia programu. Unaweza kuzima biometriki na kufungua kwa nenosiri.

    Matumizi yenye uwajibikaji
    Usifanye udanganyifu, kusumbua wanachama, kujifanya mtu mwingine, kutumia vibaya taarifa binafsi, kuingilia programu au kujaribu ufikiaji usioidhinishwa. Tunaweza kuzuia ufikiaji kuchunguza matumizi mabaya au kulinda wanachama kwa mujibu wa sheria. Hifadhi rekodi za makubaliano na uwasilishe migogoro kwa mshiriki mwingine na support@kash.ke.

    Upatikanaji na mabadiliko
    Vipengele vinaweza kubadilika na huduma inaweza kukatika. Masharti haya hayaondoi haki zinazolindwa na sheria. Mabadiliko makubwa yawasilishwe kabla ya kuanza kutumika.
""".trimIndent()
