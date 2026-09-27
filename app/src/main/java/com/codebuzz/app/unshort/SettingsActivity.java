package com.codebuzz.app.unshort;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Button enableServiceButton = findViewById(R.id.enableServiceButton);
        enableServiceButton.setOnClickListener(v -> AccessibilityDisclosure.showThenOpenSettings(this));

        RadioGroup modeGroup = findViewById(R.id.shortFormModeGroup);
        modeGroup.check(ShortFormMode.TRACK.equals(ShortFormMode.get(this))
                ? R.id.modeTrackRadio : R.id.modeBlockRadio);
        modeGroup.setOnCheckedChangeListener((group, checkedId) ->
                ShortFormMode.set(this, checkedId == R.id.modeTrackRadio
                        ? ShortFormMode.TRACK : ShortFormMode.BLOCK));

        // --- Preset Categories UI ---
        @SuppressLint({"MissingInflatedId", "LocalSuppress"}) ListView appListView = findViewById(R.id.appListView);
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        List<ApplicationInfo> userApps = new ArrayList<>();
        for (ApplicationInfo app : apps) {
            if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                userApps.add(app);
            }
        }
        TimeLimitManager tlm = TimeLimitManager.getInstance(this);
        LayoutInflater inflater = LayoutInflater.from(this);
        appListView.setAdapter(new ArrayAdapter<ApplicationInfo>(this, R.layout.item_app_category, userApps) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = convertView != null ? convertView : inflater.inflate(R.layout.item_app_category, parent, false);
                TextView text1 = view.findViewById(android.R.id.text1);
                TextView text2 = view.findViewById(android.R.id.text2);
                Spinner spinner = view.findViewById(R.id.categorySpinner);

                ApplicationInfo app = getItem(position);
                String pkg = app.packageName;
                text1.setText(pm.getApplicationLabel(app));
                text2.setText(pkg);

                ArrayAdapter<String> catAdapter = new ArrayAdapter<>(SettingsActivity.this, android.R.layout.simple_spinner_item, new String[]{TimeLimitManager.CATEGORY_SOCIAL, TimeLimitManager.CATEGORY_GAMES, TimeLimitManager.CATEGORY_PRODUCTIVITY, TimeLimitManager.CATEGORY_OTHER});
                catAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinner.setAdapter(catAdapter);
                String currentCat = tlm.getAppCategory(pkg);
                spinner.setSelection(catAdapter.getPosition(currentCat));
                spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                        String selectedCat = (String) parent.getItemAtPosition(pos);
                        tlm.setAppCategory(pkg, selectedCat);
                    }
                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });
                return view;
            }
        });
    }
}
