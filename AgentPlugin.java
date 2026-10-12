package com.roohu.phoneagent;

import android.content.Intent;
import android.provider.Settings;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "Agent")
public class AgentPlugin extends Plugin {
    @PluginMethod
    public void isEnabled(PluginCall call) {
        JSObject r = new JSObject();
        r.put("enabled", AgentAccessibilityService.instance != null);
        call.resolve(r);
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    @PluginMethod
    public void run(PluginCall call) {
        AgentAccessibilityService s = AgentAccessibilityService.instance;
        if (s == null) { call.reject("Service is off"); return; }
        if (!s.runPlan(call.getString("plan", "[]"))) { call.reject("Agent is busy or the plan is bad"); return; }
        call.resolve();
    }

    @PluginMethod
    public void status(PluginCall call) {
        AgentAccessibilityService s = AgentAccessibilityService.instance;
        JSObject r = new JSObject();
        r.put("status", s == null ? "{}" : s.getStatusJson());
        call.resolve(r);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        AgentAccessibilityService s = AgentAccessibilityService.instance;
        if (s != null) s.stopNow();
        call.resolve();
    }

    @PluginMethod
    public void runAgent(PluginCall call) {
        AgentAccessibilityService s = AgentAccessibilityService.instance;
        if (s == null) { call.reject("Service is off"); return; }
        if (!s.runAgent(call.getString("goal", ""), call.getString("base", ""), call.getString("key", ""), call.getString("model", ""))) {
            call.reject("Busy"); return;
        }
        call.resolve();
    }
}
