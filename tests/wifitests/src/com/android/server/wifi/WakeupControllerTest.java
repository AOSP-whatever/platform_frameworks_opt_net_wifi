/*
 * Copyright 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiScanner;
import android.os.test.TestLooper;
import android.provider.Settings;

import com.android.server.wifi.util.ScanResultUtil;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

/**
 * Unit tests for {@link WakeupController}.
 */
public class WakeupControllerTest {

    @Mock private Context mContext;
    @Mock private WakeupLock mWakeupLock;
    @Mock private WakeupEvaluator mWakeupEvaluator;
    @Mock private WakeupOnboarding mWakeupOnboarding;
    @Mock private WifiConfigStore mWifiConfigStore;
    @Mock private WifiInjector mWifiInjector;
    @Mock private WifiScanner mWifiScanner;
    @Mock private WifiConfigManager mWifiConfigManager;
    @Mock private FrameworkFacade mFrameworkFacade;
    @Mock private WifiSettingsStore mWifiSettingsStore;

    private TestLooper mLooper;
    private WakeupController mWakeupController;

    /** Initialize objects before each test run. */
    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mWifiInjector.getWifiScanner()).thenReturn(mWifiScanner);
        when(mWifiInjector.getWifiSettingsStore()).thenReturn(mWifiSettingsStore);

        mLooper = new TestLooper();
    }

    /** Initializes the wakeupcontroller in the given `enabled` state. */
    private void initializeWakeupController(boolean enabled) {
        int settingsValue = enabled ? 1 : 0;
        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.WIFI_WAKEUP_ENABLED, 0)).thenReturn(settingsValue);
        when(mWakeupOnboarding.isOnboarded()).thenReturn(true);
        mWakeupController = new WakeupController(mContext,
                mLooper.getLooper(),
                mWakeupLock,
                mWakeupEvaluator,
                mWakeupOnboarding,
                mWifiConfigManager,
                mWifiConfigStore,
                mWifiInjector,
                mFrameworkFacade);
    }

    private ScanResult createOpenScanResult(String ssid) {
        ScanResult scanResult = new ScanResult();
        scanResult.SSID = ssid;
        scanResult.capabilities = "";
        return scanResult;
    }

    private void verifyDoesNotEnableWifi() {
        verify(mWifiSettingsStore, never()).handleWifiToggled(true /* wifiEnabled */);
    }

    /**
     * Verify WakeupController is enabled when the settings toggle is true.
     */
    @Test
    public void verifyEnabledWhenToggledOn() {
        initializeWakeupController(true /* enabled */);

        assertTrue(mWakeupController.isEnabled());
    }

    /**
     * Verify WakeupController is disabled when the settings toggle is false.
     */
    @Test
    public void verifyDisabledWhenToggledOff() {
        initializeWakeupController(false /* enabled */);

        assertFalse(mWakeupController.isEnabled());
    }

    /**
     * Verify WakeupController registers its store data with the WifiConfigStore on construction.
     */
    @Test
    public void registersWakeupConfigStoreData() {
        initializeWakeupController(true /* enabled */);
        verify(mWifiConfigStore).registerStoreData(any(WakeupConfigStoreData.class));
    }

    /**
     * Verify that dump calls also dump the state of the WakeupLock.
     */
    @Test
    public void dumpIncludesWakeupLock() {
        initializeWakeupController(true /* enabled */);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(stream);
        mWakeupController.dump(null, writer, null);

        verify(mWakeupLock).dump(null, writer, null);
    }

    /**
     * Verify that start initializes the wakeup lock.
     */
    @Test
    public void startInitializesWakeupLock() {
        initializeWakeupController(true /* enabled */);
        mWakeupController.start();
        verify(mWakeupLock).initialize(any());
    }

    /**
     * Verify that start does not initialize the wakeup lock when feature is disabled.
     */
    @Test
    public void startDoesNotInitializeWakeupLockWhenDisabled() {
        initializeWakeupController(false /* enabled */);
        mWakeupController.start();
        verify(mWakeupLock, never()).initialize(any());
    }

    /**
     * Verify that start does not re-initialize the wakeup lock if the controller is already active.
     */
    @Test
    public void startDoesNotInitializeWakeupLockIfAlreadyActive() {
        initializeWakeupController(true /* enabled */);
        InOrder inOrder = Mockito.inOrder(mWakeupLock);

        mWakeupController.start();
        inOrder.verify(mWakeupLock).initialize(any());

        mWakeupController.stop();
        mWakeupController.start();
        inOrder.verify(mWakeupLock, never()).initialize(any());
    }

    /**
     * Verify that start registers the scan listener on the wifi scanner.
     */
    @Test
    public void startRegistersScanListener() {
        initializeWakeupController(true /* enabled */);
        mWakeupController.start();
        verify(mWifiScanner).registerScanListener(any());
    }

    /**
     * Verify that stop deregisters the scan listener from the wifi scanner.
     */
    @Test
    public void stopDeresgistersScanListener() {
        initializeWakeupController(true /* enabled */);
        mWakeupController.start();
        mWakeupController.stop();
        verify(mWifiScanner).deregisterScanListener(any());
    }

    /**
     * Verify that reset sets active to false.
     *
     * <p>This is accomplished by initiating another call to start and verifying that the wakeup
     * lock is re-initialized.
     */
    @Test
    public void resetSetsActiveToFalse() {
        initializeWakeupController(true /* enabled */);
        InOrder inOrder = Mockito.inOrder(mWakeupLock);

        mWakeupController.start();
        inOrder.verify(mWakeupLock).initialize(any());

        mWakeupController.stop();
        mWakeupController.reset();
        mWakeupController.start();
        inOrder.verify(mWakeupLock).initialize(any());
    }

    /**
     * Verify that the wakeup lock is initialized with the intersection of ScanResults and saved
     * networks.
     */
    @Test
    public void startInitializesWakeupLockWithSavedScanResults() {
        String ssid1 = "ssid 1";
        String ssid2 = "ssid 2";
        String quotedSsid = ScanResultUtil.createQuotedSSID(ssid1);

        // saved configs
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork(quotedSsid);
        openNetwork.getNetworkSelectionStatus().setHasEverConnected(true);
        WifiConfiguration wepNetwork = WifiConfigurationTestUtil.createWepNetwork();
        wepNetwork.getNetworkSelectionStatus().setHasEverConnected(true);
        when(mWifiConfigManager.getSavedNetworks())
                .thenReturn(Arrays.asList(openNetwork, wepNetwork));

        // scan results from most recent scan
        ScanResult savedScanResult = createOpenScanResult(ssid1);
        ScanResult unsavedScanResult = createOpenScanResult(ssid2);
        when(mWifiScanner.getSingleScanResults())
                .thenReturn(Arrays.asList(savedScanResult, unsavedScanResult));

        // intersection of most recent scan + saved configs
        Collection<ScanResultMatchInfo> expectedMatchInfos =
                Collections.singleton(ScanResultMatchInfo.fromScanResult(savedScanResult));

        initializeWakeupController(true /* enabled */);
        mWakeupController.start();
        verify(mWakeupLock).initialize(eq(expectedMatchInfos));
    }

    /**
     * Verify that onResults updates the WakeupLock.
     */
    @Test
    public void onResultsUpdatesWakeupLock() {
        ScanResult scanResult = new ScanResult();
        scanResult.SSID = "open ssid 1";
        scanResult.capabilities = "";
        ScanResult[] scanResults = new ScanResult[1];
        scanResults[0] = scanResult;

        // scanlistener input
        WifiScanner.ScanData[] scanDatas = new WifiScanner.ScanData[1];
        scanDatas[0] = new WifiScanner.ScanData(0 /* id */, 0 /* flags */, 0 /* bucketsScanned */,
                true /* allChannelsScanned */, scanResults);

        initializeWakeupController(true /* enabled */);
        mWakeupController.start();

        ArgumentCaptor<WifiScanner.ScanListener> scanListenerArgumentCaptor =
                ArgumentCaptor.forClass(WifiScanner.ScanListener.class);

        verify(mWifiScanner).registerScanListener(scanListenerArgumentCaptor.capture());
        WifiScanner.ScanListener scanListener = scanListenerArgumentCaptor.getValue();

        scanListener.onResults(scanDatas);

        ScanResultMatchInfo expectedMatchInfo = ScanResultMatchInfo.fromScanResult(scanResult);
        verify(mWakeupLock).update(eq(Collections.singleton(expectedMatchInfo)));
    }

    /**
     * Verify that the controller searches for viable networks during onResults when WakeupLock is
     * empty.
     */
    @Test
    public void onResultsSearchesForViableNetworkWhenWakeupLockIsEmpty() {
        ScanResult scanResult = new ScanResult();
        scanResult.SSID = "open ssid 1";
        scanResult.capabilities = "";
        ScanResult[] scanResults = new ScanResult[1];
        scanResults[0] = scanResult;

        // scanlistener input
        WifiScanner.ScanData[] scanDatas = new WifiScanner.ScanData[1];
        scanDatas[0] = new WifiScanner.ScanData(0 /* id */, 0 /* flags */, 0 /* bucketsScanned */,
                true /* allChannelsScanned */, scanResults);

        // empty wakeup lock
        when(mWakeupLock.isEmpty()).thenReturn(true);

        // do not find viable network
        when(mWakeupEvaluator.findViableNetwork(any(), any())).thenReturn(null);

        initializeWakeupController(true /* enabled */);
        mWakeupController.start();

        ArgumentCaptor<WifiScanner.ScanListener> scanListenerArgumentCaptor =
                ArgumentCaptor.forClass(WifiScanner.ScanListener.class);

        verify(mWifiScanner).registerScanListener(scanListenerArgumentCaptor.capture());
        WifiScanner.ScanListener scanListener = scanListenerArgumentCaptor.getValue();

        scanListener.onResults(scanDatas);
        verify(mWakeupEvaluator).findViableNetwork(any(), any());

        verifyDoesNotEnableWifi();
    }

    /**
     * Verify that the controller only updates the WakeupLock if the user is onboarded.
     */
    @Test
    public void onResultsDoesNotUpdateIfNotOnboarded() {
        ScanResult scanResult = new ScanResult();
        scanResult.SSID = "open ssid 1";
        scanResult.capabilities = "";
        ScanResult[] scanResults = new ScanResult[1];
        scanResults[0] = scanResult;

        // scanlistener input
        WifiScanner.ScanData[] scanDatas = new WifiScanner.ScanData[1];
        scanDatas[0] = new WifiScanner.ScanData(0 /* id */, 0 /* flags */, 0 /* bucketsScanned */,
                true /* allChannelsScanned */, scanResults);

        initializeWakeupController(true /* enabled */);
        when(mWakeupOnboarding.isOnboarded()).thenReturn(false);
        mWakeupController.start();

        ArgumentCaptor<WifiScanner.ScanListener> scanListenerArgumentCaptor =
                ArgumentCaptor.forClass(WifiScanner.ScanListener.class);

        verify(mWifiScanner).registerScanListener(scanListenerArgumentCaptor.capture());
        WifiScanner.ScanListener scanListener = scanListenerArgumentCaptor.getValue();

        scanListener.onResults(scanDatas);

        verify(mWakeupLock, never()).isEmpty();
        verify(mWakeupLock, never()).update(any());

        verifyDoesNotEnableWifi();
    }

    /**
     * Verify that the controller enables wifi and notifies user when all criteria are met.
     */
    @Test
    public void onResultsEnablesWifi() {
        ScanResult scanResult = new ScanResult();
        scanResult.SSID = "open ssid 1";
        scanResult.capabilities = "";
        ScanResult[] scanResults = new ScanResult[1];
        scanResults[0] = scanResult;

        // scanlistener input
        WifiScanner.ScanData[] scanDatas = new WifiScanner.ScanData[1];
        scanDatas[0] = new WifiScanner.ScanData(0 /* id */, 0 /* flags */, 0 /* bucketsScanned */,
                true /* allChannelsScanned */, scanResults);

        // empty wakeup lock
        when(mWakeupLock.isEmpty()).thenReturn(true);

        // find viable network
        when(mWakeupEvaluator.findViableNetwork(any(), any())).thenReturn(scanResult);

        initializeWakeupController(true /* enabled */);
        mWakeupController.start();

        ArgumentCaptor<WifiScanner.ScanListener> scanListenerArgumentCaptor =
                ArgumentCaptor.forClass(WifiScanner.ScanListener.class);

        verify(mWifiScanner).registerScanListener(scanListenerArgumentCaptor.capture());
        WifiScanner.ScanListener scanListener = scanListenerArgumentCaptor.getValue();

        scanListener.onResults(scanDatas);
        verify(mWakeupEvaluator).findViableNetwork(any(), any());

        verify(mWifiSettingsStore).handleWifiToggled(true /* wifiEnabled */);
    }
}
