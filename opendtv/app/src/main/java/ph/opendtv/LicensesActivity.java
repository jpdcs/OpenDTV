package ph.opendtv;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** The app's own license plus the licenses of the open-source components it uses. Texts live in assets/licenses. */
public class LicensesActivity extends AppCompatActivity {
    private static final class Lib {
        final String name, version, license, url, file;
        Lib(String name, String version, String license, String url, String file) {
            this.name = name; this.version = version; this.license = license; this.url = url; this.file = file;
        }
    }

    private static final Lib[] LIBS = {
            new Lib("web-bml (BML data-broadcast browser)", "1.0.1", "MIT", "https://github.com/otya128/web-bml", "web-bml.txt"),
            new Lib("arib-mmt-tlv-ts", "2.0.2", "MIT", "https://github.com/otya128/arib-mmt-tlv-ts", "arib-mmt-tlv-ts.txt"),
            new Lib("css (reworkcss, otya128 fork)", "3.0.2", "MIT", "https://github.com/otya128/reworkcss-css", "css.txt"),
            new Lib("fast-xml-parser", "4.5.7", "MIT", "https://github.com/NaturalIntelligence/fast-xml-parser", "fast-xml-parser.txt"),
            new Lib("fflate", "0.8.3", "MIT", "https://github.com/101arrowz/fflate", "fflate.txt"),
            new Lib("crc-32", "1.2.2", "Apache-2.0", "https://github.com/SheetJS/js-crc32", "crc-32.txt"),
            new Lib("AndroidX Media3 (ExoPlayer, UI)", "1.7.1", "Apache-2.0", "https://github.com/androidx/media", "apache-2.0.txt"),
            new Lib("aribb24.js (styled caption renderer)", "", "MIT", "https://github.com/monyone/aribb24.js", "aribb24-js.txt"),
            new Lib("Kosugi / Kosugi Maru fonts (BML + captions)", "", "Apache-2.0", "https://fonts.google.com/specimen/Kosugi", "apache-2.0.txt"),
            new Lib("NextLib + FFmpeg (software decoders)", "0.9.0", "GPL-3.0", "https://github.com/anilbeesetti/nextlib", "nextlib.txt"),
            new Lib("AndroidX AppCompat / RecyclerView / WebKit", "", "Apache-2.0", "https://developer.android.com/jetpack/androidx", "apache-2.0.txt"),
            new Lib("Material Components for Android", "1.12.0", "Apache-2.0", "https://github.com/material-components/material-components-android", "apache-2.0.txt"),
            new Lib("Kotlin standard library", "2.1.21", "Apache-2.0", "https://kotlinlang.org", "apache-2.0.txt"),
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_licenses);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        LinearLayout list = findViewById(R.id.list);
        list.addView(header("OpenDTV", false));
        list.addView(card("OpenDTV", "MIT License", null, "app.txt", true, list));
        list.addView(header("Third-party components", true));
        TextView note = new TextView(this);
        note.setText("The data-broadcast viewer (web-bml and its dependencies) is bundled in assets/bml/bml.js.");
        // TextViewCompat: TextView.setTextAppearance(int) is Android 6.0+ and crashed this screen on 5.x
        TextViewCompat.setTextAppearance(note, com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        note.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        note.setPadding(dp(4), 0, dp(4), dp(8));
        list.addView(note);
        for (Lib l : LIBS) {
            list.addView(card(l.name + (l.version.isEmpty() ? "" : "  " + l.version), l.license, l.url, l.file, false, list));
        }
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private int color(int attr) {
        return MaterialColors.getColor(this, attr, 0xFF888888);
    }

    /** Material 3 section label (primary colour, label style). */
    private TextView header(String text, boolean topGap) {
        TextView t = new TextView(this);
        t.setText(text);
        TextViewCompat.setTextAppearance(t, com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        t.setTextColor(color(com.google.android.material.R.attr.colorPrimary));
        t.setPadding(dp(4), dp(topGap ? 20 : 8), dp(4), dp(8));
        return t;
    }

    /** One filled M3 card: name + license line, tap to expand the project link and the full license text. */
    private View card(String name, String license, final String url, String file, boolean expanded, final ViewGroup parent) {
        final View v = getLayoutInflater().inflate(R.layout.item_license, parent, false);
        ((TextView) v.findViewById(R.id.name)).setText(name);
        ((TextView) v.findViewById(R.id.license)).setText(license);
        MaterialButton link = v.findViewById(R.id.link);
        if (url == null) {
            link.setVisibility(View.GONE);
        } else {
            link.setText(url.replace("https://", ""));
            link.setOnClickListener(x -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                }
            });
        }
        ((TextView) v.findViewById(R.id.body)).setText(read(file));
        final View details = v.findViewById(R.id.details);
        final View chevron = v.findViewById(R.id.chevron);
        setExpanded(details, chevron, expanded, false, parent);
        v.findViewById(R.id.header).setOnClickListener(x ->
                setExpanded(details, chevron, details.getVisibility() != View.VISIBLE, true, parent));
        return v;
    }

    private void setExpanded(View details, View chevron, boolean open, boolean animate, ViewGroup parent) {
        if (animate) TransitionManager.beginDelayedTransition(parent, new AutoTransition().setDuration(180));
        details.setVisibility(open ? View.VISIBLE : View.GONE);
        chevron.setRotation(open ? 180f : 0f);
    }

    private String read(String file) {
        try (InputStream in = getAssets().open("licenses/" + file)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return new String(o.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "(license text not found: " + file + ")";
        }
    }
}
