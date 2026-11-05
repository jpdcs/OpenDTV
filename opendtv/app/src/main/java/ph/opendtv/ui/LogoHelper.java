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
        String filename = matchLogo(channelName);
        if (filename == null) filename = matchLogo(networkName);
        if (filename == null) return null;

        try (InputStream is = context.getAssets().open(DIR + filename)) {
            return Drawable.createFromStream(is, filename);
        } catch (Exception e) {
            Log.w(TAG, "Failed to load logo " + filename, e);
            return null;
        }
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
            new Rule("rptv.webp",            "rptv"),
            new Rule("rpn.webp",             "=rpn", "radiophilippinesnetwork"),
            new Rule("prtv_prime_media.webp","prtv", "primemedia"),
            new Rule("beam.webp",            "=beam", "broadcastenterprises"),
            new Rule("a2z.webp",             "a2z", "zoebroadcasting"),
            new Rule("stv.webp",             "=stv"),
            new Rule("untv.webp",            "untv", "progressivebroadcasting"),
            new Rule("ptni.webp",            "=ptni"),
            new Rule("ptv4.webp",            "=ptv", "=ptv4", "peoplestelevision"),

            // --- GMA group ---
            new Rule("gtv.webp",             "=gtv"),
            new Rule("heart_of_asia.webp",   "heartofasia"),
            new Rule("i_heart_movies.webp",  "iheartmovies"),
            new Rule("gma.webp",             "=gma", "gmanetwork"),

            // --- ALLTV / IBC / TV5 / NET25 ---
            new Rule("alltv.webp",           "=alltv", "=alltv2", "=all tv", "advancedmediabroadcasting"),
            new Rule("ibc_13.webp",          "ibc", "intercontinentalbroadcasting"),
            new Rule("congress_tv.jpg",     "congress"),
            new Rule("senate_tv.webp",       "senate"),
            new Rule("dwan_tv.webp",         "dwan"),
            new Rule("one_sports.webp",      "onesports"),
            new Rule("tv5.webp",             "=tv5", "=one seg", "cignal", "mediascape",
                    "interactivebroadcastmedia", "nationbroadcasting"),
            new Rule("net_25.webp",          "net25", "=net 25", "eaglebroadcasting"),
            new Rule("beam.webp",          "beam", "reserved"),

            // --- Solar / Free Air / misc. ---
            new Rule("solarflix.jpg",       "solarflix"),
            new Rule("solarsports.webp",     "solarsports"),
            new Rule("deped_tv.webp",        "deped"),
            new Rule("shop_tv.jpg",         "shoptv"),
            new Rule("knowledge_channel.webp","knowledge"),
            new Rule("d8tv.webp",            "d8tv"),
            new Rule("rock_of_manila_tv.webp","rockofmanila", "rockmnl"),
            new Rule("radyo_bandido.jpg",   "radyobandido"),
            new Rule("timeless_tv.webp",     "timeless"),
            new Rule("tv_maria.webp",        "tvmaria", "=maria"),
            new Rule("rj_digitv.webp",       "rjdigitv", "digitv", "=rj", "freeairbroadcasting"),

            // --- radio-TV / news ---
            new Rule("aliw_channel.webp",    "aliw"),
            new Rule("dwiz_news.webp",       "dwiz"),
            new Rule("dzmm_teleradyo.webp",  "dzmm", "teleradyo"),
            new Rule("bilyonaryo_news.webp",  "bilyonaryo"),

            // --- religious / community ---
            new Rule("hope_channel.webp",    "=hope", "hopechannel", "gatewayuhf"),
            new Rule("gnn.webp",             "=gnn", "tiger48", "globalsatellitetechnology"),
            new Rule("cctn.webp",            "=cctn"),
            new Rule("veritas_tv.webp",      "veritas"),
            new Rule("truth_channel.webp",   "truthchannel", "=truth"),
            new Rule("inctv.webp",           "inctv", "=inc tv", "christianera"),
            new Rule("light_tv.webp",        "lighttv", "lightv"),
            new Rule("island_living.webp",   "islandliving"),
            new Rule("cltv_36.webp",         "cltv", "radioworldbroadcasting"),

            // --- provincial / independent ---
            new Rule("cab_tv.webp",          "cabtv"),
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