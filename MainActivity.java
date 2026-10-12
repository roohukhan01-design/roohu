package com.roohu.phoneagent;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(AgentPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
