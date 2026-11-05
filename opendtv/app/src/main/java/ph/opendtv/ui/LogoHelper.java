package ph.opendtv.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.io.InputStream;
import java.util.Locale;

/**
 * Maps a DTV channel (name + operating company) to a bundled logo in assets/dtvlogo/.
 * Names follow the NTC list at:
 * https://en.wikipedia.org/wiki/List_of_digital_television_stations_in_the_Philippines
 *
 * Matching rules:
 *  - The channel NAME is tried first. The company/network name is only a fallback,
 *    so e.g. "RPTV" carried by "TV5 Network, Inc." shows the RPTV logo, not TV5.
 *  - Tokens starting with "=" match whole words/phrases only (avoids "4", "13", "inc",
 *    "rj", "stv" etc. matching inside unrelated text).
 *  - All other tokens match as a substring of the name with spaces/punctuation removed,
 *    so "I Heart Movies", "iheartmovies" and "I-Heart Movies" are equivalent.
 *  - Rules are ordered most-specific first (rptv before ptv, a2z before one seg, ...).
 */
public final class LogoHelper {
    private static final String TAG = "LogoHelper";
    private static final String DIR = "dtvlogo/";

    private LogoHelper() {}

    public static Drawable getLogo(Context context, String channelName, String networkName) {
        String filename = fromBundle(context, channelName, networkName);
        if (filename == null) filename = matchLogo(channelName);
        if (filename == null) filename = matchLogo(networkName);
        if (filename == null) return null;

        try (InputStream is = context.getAssets().open(DIR + filename)) {
            return Drawable.createFromStream(is, filename);
        } catch (Exception e) {
            Log.w(TAG, "Failed to load logo " + filename, e);
            return null;
        }
    }

    private static String[] logoFiles;   // assets/dtvlogo, listed once

    /**
     * The over-the-air logic bundle's pick for this channel, as a file in assets/dtvlogo, or null to use the rules
     * below. The bundle names a logo by its base name ("gma"), so it doesn't care how the image is stored.
     */
    private static String fromBundle(Context context, String channelName, String networkName) {
        try {
            ph.opendtv.ota.AppLogic logic = ph.opendtv.ota.LogicBundles.get(context).logic();
            String base = logic == null ? null : logic.logoFor(channelName, networkName);
            if (base == null || base.isEmpty()) return null;
            if (logoFiles == null) logoFiles = context.getAssets().list(DIR.substring(0, DIR.length() - 1));
            for (String f : logoFiles) {
                int dot = f.lastIndexOf('.');
                if (dot > 0 && f.substring(0, dot).equals(base)) return f;   // only files that are really there
            }
        } catch (Throwable t) {   // a broken bundle must not take the channel list down with it
            Log.w(TAG, "logic bundle failed in logoFor", t);
        }
        return null;
    }

    // ---------------------------------------------------------------------

    private static final class Rule {
        final String file;
        final String[] tokens;

        Rule(String file, String... tokens) {
            this.file = file;
            this.tokens = tokens;
        }
    }

    private static final Rule[] RULES = {
            // --- ambiguous / overlapping names first ---
            new Rule("rptv.png",            "rptv"),
            new Rule("rpn.png",             "=rpn", "radiophilippinesnetwork"),
            new Rule("prtv_prime_media.png","prtv", "primemedia"),
            new Rule("beam.png",            "=beam", "broadcastenterprises"),
            new Rule("a2z.png",             "a2z", "zoebroadcasting"),
            new Rule("stv.png",             "=stv"),
            new Rule("untv.png",            "untv", "progressivebroadcasting"),
            new Rule("ptni.png",            "=ptni"),
            new Rule("ptv4.png",            "=ptv", "=ptv4", "peoplestelevision"),

            // --- GMA group ---
            new Rule("gtv.png",             "=gtv"),
            new Rule("heart_of_asia.png",   "heartofasia"),
            new Rule("i_heart_movies.png",  "iheartmovies"),
            new Rule("gma.png",             "=gma", "gmanetwork"),

            // --- ALLTV / IBC / TV5 / NET25 ---
            new Rule("alltv.png",           "=alltv", "=alltv2", "=all tv", "advancedmediabroadcasting"),
            new Rule("ibc_13.png",          "ibc", "intercontinentalbroadcasting"),
            new Rule("congress_tv.jpg",     "congress"),
            new Rule("senate_tv.png",       "senate"),
            new Rule("dwan_tv.png",         "dwan"),
            new Rule("one_sports.png",      "onesports"),
            new Rule("tv5.png",             "=tv5", "=one seg", "cignal", "mediascape",
                    "interactivebroadcastmedia", "nationbroadcasting"),
            new Rule("net_25.png",          "net25", "=net 25", "eaglebroadcasting"),
            new Rule("beam.png",          "beam", "reserved"),

            // --- Solar / Free Air / misc. ---
            new Rule("solarflix.jpg",       "solarflix"),
            new Rule("solarsports.png",     "solarsports"),
            new Rule("deped_tv.png",        "deped"),
            new Rule("shop_tv.jpg",         "shoptv"),
            new Rule("knowledge_channel.png","knowledge"),
            new Rule("d8tv.png",            "d8tv"),
            new Rule("rock_of_manila_tv.png","rockofmanila", "rockmnl"),
            new Rule("radyo_bandido.jpg",   "radyobandido"),
            new Rule("timeless_tv.png",     "timeless"),
            new Rule("tv_maria.png",        "tvmaria", "=maria"),
            new Rule("rj_digitv.png",       "rjdigitv", "digitv", "=rj", "freeairbroadcasting"),

            // --- radio-TV / news ---
            new Rule("aliw_channel.png",    "aliw"),
            new Rule("dwiz_news.png",       "dwiz"),
            new Rule("dzmm_teleradyo.png",  "dzmm", "teleradyo"),
            new Rule("bilyonaryo_news.png",  "bilyonaryo"),

            // --- religious / community ---
            new Rule("hope_channel.png",    "=hope", "hopechannel", "gatewayuhf"),
            new Rule("gnn.png",             "=gnn", "tiger48", "globalsatellitetechnology"),
            new Rule("cctn.png",            "=cctn"),
            new Rule("veritas_tv.png",      "veritas"),
            new Rule("truth_channel.png",   "truthchannel", "=truth"),
            new Rule("inctv.png",           "inctv", "=inc tv", "christianera"),
            new Rule("light_tv.png",        "lighttv", "lightv"),
            new Rule("island_living.png",   "islandliving"),
            new Rule("cltv_36.png",         "cltv", "radioworldbroadcasting"),

            // --- provincial / independent ---
            new Rule("cab_tv.png",          "cabtv"),
            new Rule("dztp_tirad_pass.jpg", "dztp", "=tpn", "tiradpass"),
            new Rule("rng_luzon.jpg",       "rngluzon", "=rng"),
            new Rule("jao_tv.jpg",          "=jao", "jaotv"),
            new Rule("emedia.jpg",          "emedia"),
            new Rule("gbpi_11.jpg",         "gbpi", "goldenbroadcasting"),
    };

    static String matchLogo(String raw) {
        if (raw == null) return null;
        String lower = raw.toLowerCase(Locale.US);
        String spaced = " " + lower.replaceAll("[^a-z0-9]+", " ").trim() + " ";
        String compact = lower.replaceAll("[^a-z0-9]", "");
        if (compact.isEmpty()) return null;

        for (Rule rule : RULES) {
            for (String t : rule.tokens) {
                boolean hit = t.startsWith("=")
                        ? spaced.contains(" " + t.substring(1) + " ")
                        : compact.contains(t);
                if (hit) return rule.file;
            }
        }
        return null;
    }
}