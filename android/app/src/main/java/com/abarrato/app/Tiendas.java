package com.abarrato.app;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Las cadenas que Abarrato compara por ahora. */
final class Tiendas {
    static final List<String> IDS = Collections.unmodifiableList(
            Arrays.asList("JUMBO", "SANTA_ISABEL", "LIDER", "UNIMARC", "TOTTUS", "ALVI", "CUGAT", "DEL_PEDREGAL"));

    private Tiendas() {}

    static boolean incluida(String comercio) {
        return comercio != null && IDS.contains(comercio);
    }

    static String nombre(String comercio) {
        return switch (comercio) {
            case "JUMBO" -> "Jumbo";
            case "SANTA_ISABEL" -> "Santa Isabel";
            case "LIDER" -> "Líder";
            case "UNIMARC" -> "Unimarc";
            case "TOTTUS" -> "Tottus";
            case "ALVI" -> "Alvi";
            case "CUGAT" -> "Cugat";
            case "DEL_PEDREGAL" -> "Del Pedregal";
            default -> comercio;
        };
    }

    /** Texto breve para la insignia circular de la tarjeta "Tu próxima compra". */
    static String insignia(String comercio) {
        return switch (comercio) {
            case "JUMBO" -> "Jumbo";
            case "SANTA_ISABEL" -> "Santa\nIsabel";
            case "LIDER" -> "Lider";
            case "UNIMARC" -> "Unimarc";
            case "TOTTUS" -> "Tottus";
            case "ALVI" -> "Alvi";
            case "CUGAT" -> "Cugat";
            case "DEL_PEDREGAL" -> "Del\nPedregal";
            default -> "🛒";
        };
    }

    static int logo(String comercio) {
        return switch (comercio) {
            case "JUMBO" -> R.drawable.logo_jumbo;
            case "SANTA_ISABEL" -> R.drawable.logo_santa_isabel;
            case "LIDER" -> R.drawable.logo_lider;
            case "UNIMARC" -> R.drawable.logo_unimarc;
            case "TOTTUS" -> R.drawable.logo_tottus;
            case "ALVI" -> R.drawable.logo_alvi;
            case "CUGAT" -> R.drawable.logo_cugat;
            case "DEL_PEDREGAL" -> R.drawable.logo_del_pedregal;
            default -> R.drawable.ic_abarrato;
        };
    }

    static int color(String comercio) {
        return switch (comercio) {
            case "JUMBO" -> 0xFF0A7A3C;
            case "SANTA_ISABEL" -> 0xFFC8102E;
            case "LIDER" -> 0xFF0B5A44;
            case "UNIMARC" -> 0xFFD71920;
            case "TOTTUS" -> 0xFF3F9C35;
            case "ALVI" -> 0xFFE30613;
            case "CUGAT" -> 0xFF1F3A5F;
            case "DEL_PEDREGAL" -> 0xFF7A2430;
            default -> 0xFF11281F;
        };
    }
}
