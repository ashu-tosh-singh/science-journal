/*
 *  Copyright 2016 Google Inc. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.google.android.apps.forscience.whistlepunk;

import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import android.text.TextUtils;
import android.util.ArrayMap;
import com.google.android.apps.forscience.javalib.Consumer;
import com.google.android.apps.forscience.javalib.Delay;
import com.google.android.apps.forscience.javalib.FallibleConsumer;
import com.google.android.apps.forscience.javalib.MaybeConsumer;
import com.google.android.apps.forscience.javalib.Scheduler;
import com.google.android.apps.forscience.javalib.Success;
import com.google.android.apps.forscience.whistlepunk.analytics.TrackerConstants;
import com.google.android.apps.forscience.whistlepunk.data.nano.GoosciSensorLayout;
import com.google.android.apps.forscience.whistlepunk.data.nano.GoosciSensorSpec;
import com.google.android.apps.forscience.whistlepunk.filemetadata.Experiment;
import com.google.android.apps.forscience.whistlepunk.filemetadata.Label;
import com.google.android.apps.forscience.whistlepunk.filemetadata.SensorTrigger;
import com.google.android.apps.forscience.whistlepunk.filemetadata.Trial;
import com.google.android.apps.forscience.whistlepunk.metadata.TriggerHelper;
import com.google.android.apps.forscience.whistlepunk.metadata.nano.GoosciCaption;
import com.google.android.apps.forscience.whistlepunk.metadata.nano.GoosciLabel;
import com.google.android.apps.forscience.whistlepunk.metadata.nano.GoosciSensorTriggerInformation.TriggerInformation;
import com.google.android.apps.forscience.whistlepunk.metadata.nano.GoosciSensorTriggerLabelValue;
import com.google.android.apps.forscience.whistlepunk.metadata.nano.GoosciSnapshotValue;
import com.google.android.apps.forscience.whistlepunk.sensorapi.ScalarSensor;
import com.google.android.apps.forscience.whistlepunk.sensorapi.SensorChoice;
import com.google.android.apps.forscience.whistlepunk.sensorapi.SensorEnvironment;
import com.google.android.apps.forscience.whistlepunk.sensorapi.SensorObserver;
import com.google.android.apps.forscience.whistlepunk.sensorapi.SensorRecorder;
import com.google.android.apps.forscience.whistlepunk.sensorapi.SensorStatusListener;
import com.google.android.apps.forscience.whistlepunk.sensordb.ScalarReading;
import com.google.android.apps.forscience.whistlepunk.sensors.SystemScheduler;
import com.google.android.apps.forscience.whistlepunk.wireapi.RecordingMetadata;
import com.google.android.apps.forscience.whistlepunk.wireapi.TransportableSensorOptions;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.Lists;
import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.MaybeSource;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.subjects.BehaviorSubject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Keeps track of:
 *
 * <ul>
 *   <li>All the recorders that are currently observing or recording
 *   <li>The recording state (when did the current run start, if any?)
 *   <li>The currently-selected experiment
 * </ul>
 */
public class RecorderControllerImpl implements RecorderController {
  private static final String TAG = "RecorderController";

  /**
   * Default delay to wait after the last observer stops before asking the sensor to stop
   * collecting.
   */
  // private static final Delay DEFAULT_STOP_DELAY = Delay.seconds(5);

  // TODO: remove this comment when we're sure about the delay.
  // To disable delayed stop, comment out the above line, and uncomment this one.
  private static final Delay DEFAULT_STOP_DELAY = Delay.ZERO;

  private DataController dataController;
  private final Scheduler scheduler;
  private final Clock clock;
  private final Delay stopDelay;
  private SensorAppearanceProvider appearanceProvider;
  private Map<String, StatefulRecorder> recorders = new LinkedHashMap<>();
  private final Context context;
  private final RecorderListenerRegistry registry;
  private Map<String, String> serviceObservers = new HashMap<>();
  private RecorderServiceConnection serviceConnection = null;
  private int pauseCount = 0;
  private final SensorEnvironment sensorEnvironment;
  private BehaviorSubject<Experiment> selectedExperiment = BehaviorSubject.create();
  private String currentTrialId = "";
  private boolean recordingStateChangeInProgress;
  private TriggerHelper triggerHelper;
  private boolean activityInForeground = false;
  private final Supplier<RecorderServiceConnection> connectionSupplier;

  private int nextTriggerListenerId = 0;
  private Map<Integer, TriggerFiredListener> triggerListeners = new HashMap<>();

  private Map<String, ObservedIdsListener> observedIdListeners = new ArrayMap<>();

  private BehaviorSubject<RecordingStatus> recordingStatus =
      BehaviorSubject.createDefault(RecordingStatus.INACTIVE);

  private Supplier<List<GoosciSensorLayout.SensorLayout>> layoutSupplier;

  /** The latest recorded value for each sensor */
  private Map<String, BehaviorSubject<ScalarReading>> latestValues = new HashMap<>();

  public RecorderControllerImpl(Context context) {
    this(context, AppSingleton.getInstance(context).getDataController());
  }

  private RecorderControllerImpl(Context context, DataController dataController) {
    this(
        context,
        AppSingleton.getInstance(context).getSensorEnvironment(),
        new RecorderListenerRegistry(),
        productionConnectionSupplier(context),
        dataController,
        new SystemScheduler(),
        DEFAULT_STOP_DELAY,
        AppSingleton.getInstance(context).getSensorAppearanceProvider());
  }

  // TODO: use builder?
  /**
   * @param scheduler for scheduling delayed stops if desired (to prevent sensor stop/start churn)
   * @param stopDelay how long to wait before stopping sensors.
   */
  @VisibleForTesting
  public RecorderControllerImpl(
      final Context context,
      SensorEnvironment sensorEnvironment,
      RecorderListenerRegistry listenerRegistry,
      Supplier<RecorderServiceConnection> connectionSupplier,
      DataController dataController,
      Scheduler scheduler,
      Delay stopDelay,
      SensorAppearanceProvider appearanceProvider) {
    this.context = context;
    this.sensorEnvironment = sensorEnvironment;
    registry = listenerRegistry;
    this.connectionSupplier = connectionSupplier;
    this.dataController = dataController;
    this.scheduler = scheduler;
    this.stopDelay = stopDelay;
    this.appearanceProvider = appearanceProvider;
    clock = new CurrentTimeClock();
  }

  @NonNull
  private static Supplier<RecorderServiceConnection> productionConnectionSupplier(
      final Context context) {
    return () ->
        new RecorderServiceConnectionImpl(
            context, LoggingConsumer.expectSuccess(TAG, "remote service operation"));
  }

  // TODO: Can RecorderControllerImpl eventually own / look up the triggers so they don't
  //       need to be passed in here?
  @Override
  public String startObserving(
      final String sensorId,
      final List<SensorTrigger> activeTriggers,
      SensorObserver observer,
      SensorStatusListener listener,
      final TransportableSensorOptions initialOptions,
      SensorRegistry sensorRegistry) {
    // Put the observer and listener in the registry by sensorId.
    String observerId = registry.putListeners(sensorId, observer, listener);
    StatefulRecorder sr = recorders.get(sensorId);
    if (sr != null) {
      RecorderControllerImpl.this.startObserving(sr);
      addServiceObserverIfNeeded(sensorId, activeTriggers, sensorRegistry);
    } else {
      sensorRegistry.withSensorChoice(
          TAG,
          sensorId,
          new Consumer<SensorChoice>() {
            @Override
            public void take(SensorChoice sensor) {
              final SensorRecorder recorder =
                  sensor.createRecorder(
                      context,
                      registry.makeObserverForRecorder(sensorId),
                      registry,
                      sensorEnvironment);
              recorder.applyOptions(new ReadableTransportableSensorOptions(initialOptions));
              StatefulRecorder newStatefulRecorder =
                  new StatefulRecorder(recorder, scheduler, stopDelay);
              recorders.put(sensorId, newStatefulRecorder);

              // TODO: can we avoid passing sensorRegistry so deep?
              addServiceObserverIfNeeded(sensorId, activeTriggers, sensorRegistry);
              RecorderControllerImpl.this.startObserving(newStatefulRecorder);
            }
          });
    }
    return observerId;
  }

  @Override
  public void reboot(String sensorId) {
    StatefulRecorder recorder = recorders.get(sensorId);
    if (recorder != null) {
      recorder.reboot();
    }
  }

  private void addServiceObserverIfNeeded(
      final String sensorId,
      final List<SensorTrigger> activeTriggers,
      SensorRegistry sensorRegistry) {
    if (!latestValues.containsKey(sensorId)) {
      latestValues.put(sensorId, BehaviorSubject.<ScalarReading>create());
    }

    if (!serviceObservers.containsKey(sensorId)) {
      String serviceObserverId =
          registry.putListeners(
              sensorId,
              (timestamp, data) -> {
                if (!ScalarSensor.hasValue(data)) {
                  return;
                }
                double value = ScalarSensor.getValue(data);

                // Remember latest value
                latestValues.get(sensorId).onNext(new ScalarReading(timestamp, value, sensorId));

                // Fire triggers.
                for (SensorTrigger trigger : activeTriggers) {
                  if (!isRecording() && trigger.shouldTriggerOnlyWhenRecording()) {
                    continue;
                  }
                  if (trigger.isTriggered(value)) {
                    fireSensorTrigger(trigger, timestamp, sensorRegistry);
                  }
                }
              },
              null);
      serviceObservers.put(sensorId, serviceObserverId);
    }
  }

  private List<GoosciSensorLayout.SensorLayout> buildSensorLayouts() {
    return layoutSupplier == null
        ? Collections.<GoosciSensorLayout.SensorLayout>emptyList()
        : layoutSupplier.get();
  }

  private void fireSensorTrigger(
      SensorTrigger trigger, long timestamp, SensorRegistry sensorRegistry) {
    // TODO: Think about behavior for triggers firing near the same time, especially
    // regarding start/stop recording and notes. Right now behavior may not seem repeatable
    // depending on timing of callbacks and order of triggers. b/
    boolean triggerWasFired = false;
    if (trigger.getActionType()
            == TriggerInformation.TriggerActionType.TRIGGER_ACTION_START_RECORDING
        && !isRecording()
        && getSelectedExperiment() != null) {
      if (!recordingStateChangeInProgress) {
        triggerWasFired = true;
        for (TriggerFiredListener listener : triggerListeners.values()) {
          listener.onRequestStartRecording();
        }
        // TODO: test this subscribe
        startRecording(new Intent(context, MainActivity.class), /* not user initiated */ false)
            .subscribe(LoggingConsumer.observe(TAG, "start recording with trigger"));
        WhistlePunkApplication.getUsageTracker(context)
            .trackEvent(
                TrackerConstants.CATEGORY_RUNS,
                TrackerConstants.ACTION_TRY_RECORDING_FROM_TRIGGER,
                null,
                0);
      }
    } else if (trigger.getActionType()
            == TriggerInformation.TriggerActionType.TRIGGER_ACTION_STOP_RECORDING
        && isRecording()) {
      if (!recordingStateChangeInProgress) {
        triggerWasFired = true;
        for (TriggerFiredListener listener : triggerListeners.values()) {
          listener.onRequestStopRecording(this);
        }
        stopRecording(sensorRegistry)
            .subscribe(LoggingConsumer.observe(TAG, "stop recording with trigger"));
        WhistlePunkApplication.getUsageTracker(context)
            .trackEvent(
                TrackerConstants.CATEGORY_RUNS,
                TrackerConstants.ACTION_TRY_STOP_RECORDING_FROM_TRIGGER,
                null,
                0);
      }
    } else if (trigger.getActionType()
        == TriggerInformation.TriggerActionType.TRIGGER_ACTION_NOTE) {
      triggerWasFired = true;
      addTriggerLabel(timestamp, trigger, sensorRegistry);
    } else if (trigger.getActionType()
        == TriggerInformation.TriggerActionType.TRIGGER_ACTION_ALERT) {
      if (trigger.getAlertTypes().length > 0) {
        triggerWasFired = true;
      }
      if (trigger.hasAlertType(TriggerInformation.TriggerAlertType.TRIGGER_ALERT_PHYSICAL)) {
        getTriggerHelper().doVibrateAlert(context);
      }
      if (trigger.hasAlertType(TriggerInformation.TriggerAlertType.TRIGGER_ALERT_AUDIO)) {
        getTriggerHelper().doAudioAlert(context);
      }
      // Visual alerts are not covered in RecorderControllerImpl.
    }
    // Not all triggers should actually be fired -- for example, we cannot stop recording if
    // we are not yet recording. Only call the trigger fired listeners when the event actually
    // takes place.
    if (triggerWasFired) {
      for (TriggerFiredListener listener : triggerListeners.values()) {
        listener.onTriggerFired(trigger);
      }
    }
  }

  private TriggerHelper getTriggerHelper() {
    if (triggerHelper == null) {
      triggerHelper = new TriggerHelper();
    }
    return triggerHelper;
  }

  private void addTriggerLabel(
      long timestamp, SensorTrigger trigger, SensorRegistry sensorRegistry) {
    if (getSelectedExperiment() == null) {
      return;
    }
    GoosciSensorTriggerLabelValue.SensorTriggerLabelValue labelValue =
        new GoosciSensorTriggerLabelValue.SensorTriggerLabelValue();
    labelValue.triggerInformation = trigger.getTriggerProto().triggerInformation;
    GoosciCaption.Caption caption = null;
    if (!TextUtils.isEmpty((trigger.getNoteText()))) {
      caption = new GoosciCaption.Caption();
      caption.lastEditedTimestamp = timestamp;
      caption.text = trigger.getNoteText();
    }
    labelValue.sensor = getSensorSpec(trigger.getSensorId(), sensorRegistry);
    final Label triggerLabel =
        Label.newLabelWithValue(
            timestamp, GoosciLabel.Label.ValueType.SENSOR_TRIGGER, labelValue, caption);
    if (isRecording()) {
      // Adds the label to the trial and saves the updated experiment.
      getSelectedExperiment()
          .getTrial(currentTrialId)
          .addLabel(getSelectedExperiment(), triggerLabel);
    } else {
      // Adds the label to the experiment and saves the updated experiment.
      getSelectedExperiment().addLabel(getSelectedExperiment(), triggerLabel);
    }
    dataController.updateExperiment(
        getSelectedExperiment().getExperimentId(),
        new LoggingConsumer<Success>(TAG, "add trigger label") {
          @Override
          public void success(Success value) {
            onTriggerLabelAdded(triggerLabel);
          }
        });
  }

  private GoosciSensorSpec.SensorSpec getSensorSpec(
      String sensorId, SensorRegistry sensorRegistry) {
    return sensorRegistry.getSpecForId(sensorId, appearanceProvider, context);
  }

  public Experiment getSelectedExperiment() {
    // TODO: consider using as subject more places
    return selectedExperiment.getValue();
  }

  private void onTriggerLabelAdded(Label label) {
    String trackerLabel =
        isRecording() ? TrackerConstants.LABEL_RECORD : TrackerConstants.LABEL_OBSERVE;
    WhistlePunkApplication.getUsageTracker(context)
        .trackEvent(
            TrackerConstants.CATEGORY_NOTES,
            TrackerConstants.ACTION_CREATE,
            trackerLabel,
            TrackerConstants.getLabelValueType(label));
    AppSingleton.getInstance(context).onLabelsAdded().onNext(label);
  }

  private void startObserving(StatefulRecorder sr) {
    sr.startObserving();
    updateObservedIdListeners();
  }

  @Override
  public void clearSensorTriggers(String sensorId, SensorRegistry sensorRegistry) {
    String observerId = serviceObservers.get(sensorId);
    if (!TextUtils.isEmpty(observerId)) {
      // Remove the old serviceObserver and add a new one with no triggers.
      serviceObservers.remove(sensorId);
      registry.remove(sensorId, observerId);
      addServiceObserverIfNeeded(sensorId, Collections.<SensorTrigger>emptyList(), sensorRegistry);
    }
  }

  @Override
  public void setLayoutSupplier(Supplier<List<GoosciSensorLayout.SensorLayout>> supplier) {
    layoutSupplier = supplier;
  }

  @Override
  public long getNow() {
    return sensorEnvironment.getDefaultClock().getNow();
  }

  @Override
  public void stopObserving(String sensorId, String observerId) {
    registry.remove(sensorId, observerId);
    // If there are no listeners left except for our serviceobserver, remove it.
    if (registry.countListeners(sensorId) == 1) {
      stopObservingServiceObserver(sensorId);
    }
    cleanUpUnusedRecorders();
    updateObservedIdListeners();
    if (recorders.isEmpty()) {
      SensorHistoryStorage storage = sensorEnvironment.getSensorHistoryStorage();
      storage.setMostRecentSensorIds(Lists.newArrayList(sensorId));
    }
  }

  private void stopObservingServiceObserver(String sensorId) {
    final StatefulRecorder r = recorders.get(sensorId);
    if (r != null) {
      r.stopObserving();
      if (!r.isRecording()) {
        // If it was not recording, we can also remove our service-level observers.
        if (serviceObservers.containsKey(sensorId)) {
          String serviceObserverId = serviceObservers.get(sensorId);
          registry.remove(sensorId, serviceObserverId);
          serviceObservers.remove(sensorId);
          latestValues.remove(sensorId);
        }
      }
    }
  }

  private void updateObservedIdListeners() {
    List<String> currentObservedSensorIds = getCurrentObservedSensorIds();
    for (ObservedIdsListener observedIdsListener : observedIdListeners.values()) {
      observedIdsListener.onObservedIdsChanged(currentObservedSensorIds);
    }
  }

  @Override
  public List<String> getMostRecentObservedSensorIds() {
    List<String> keys = getCurrentObservedSensorIds();
    if (keys.isEmpty()) {
      return sensorEnvironment.getSensorHistoryStorage().getMostRecentSensorIds();
    } else {
      return keys;
    }
  }

  @Override
  public Observable<RecordingStatus> watchRecordingStatus() {
    return recordingStatus;
  }

  private List<String> getCurrentObservedSensorIds() {
    return Lists.newArrayList(recorders.keySet());
  }

  // TODO: test this logic
  @Override
  public String pauseObservingAll() {
    ++pauseCount;
    if (!isRecording()) {
      for (StatefulRecorder recorder : recorders.values()) {
        recorder.stopObserving();
      }
    }
    return String.valueOf(pauseCount);
  }

  // TODO: test this logic
  @Override
  public boolean resumeObservingAll(String pauseId) {
    if (!Objects.equals(pauseId, String.valueOf(pauseCount))) {
      return false;
    }
    if (!isRecording()) {
      for (StatefulRecorder recorder : recorders.values()) {
        startObserving(recorder);
      }
    }
    return true;
  }

  @Override
  public void applyOptions(String sensorId, TransportableSensorOptions settings) {
    final StatefulRecorder r = recorders.get(sensorId);
    if (r != null) {
      r.applyOptions(new ReadableTransportableSensorOptions(settings));
    }
  }

  private void cleanUpUnusedRecorders() {
    final Iterator<Map.Entry<String, StatefulRecorder>> iter = recorders.entrySet().iterator();
    while (iter.hasNext()) {
      StatefulRecorder r = iter.next().getValue();
      if (!r.isStillRunning()) {
        iter.remove();
      }
    }
  }

  @Override
  public Completable startRecording(final Intent resumeIntent, boolean userInitiated) {
    if (isRecording() || recordingStateChangeInProgress) {
      return Completable.complete();
    }
    if (recorders.size() == 0) {
      // If the recorders are empty, then we stopped observing a sensor before we tried
      // to start recording it. This may happen if we failed to connect to an external sensor
      // and it was disconnected before recording started.
      return Completable.error(
          new RecordingStartFailedException(RecorderController.ERROR_START_FAILED, null));
    }
    // Check that all sensors are connected before starting a recording.
    for (String sensorId : recorders.keySet()) {
      if (!registry.isSourceConnectedWithoutError(sensorId)) {
        return Completable.error(
            new RecordingStartFailedException(
                RecorderController.ERROR_START_FAILED_DISCONNECTED, null));
      }
    }

    recordingStatus.onNext(
        recordingStatus.getValue().withState(RecordingState.STARTING, userInitiated));

    recordingStateChangeInProgress = true;
    return Completable.create(
        emitter ->
            withBoundRecorderService(
                new FallibleConsumer<IRecorderService>() {
                  @Override
                  public void take(final IRecorderService recorderService) throws RemoteException {
                    final DataController dataController =
                        RecorderControllerImpl.this.dataController;
                    final long creationTimeMs = clock.getNow();
                    List<GoosciSensorLayout.SensorLayout> layouts = buildSensorLayouts();

                    Trial trial =
                        Trial.newTrial(
                            creationTimeMs,
                            layouts.toArray(new GoosciSensorLayout.SensorLayout[layouts.size()]),
                            appearanceProvider,
                            context);
                    currentTrialId = trial.getTrialId();
                    getSelectedExperiment().addTrial(trial);
                    dataController.updateExperiment(
                        getSelectedExperiment().getExperimentId(),
                        new LoggingConsumer<Success>(TAG, "start trial") {
                          @Override
                          public void success(Success success) {
                            RecordingMetadata recording =
                                new RecordingMetadata(
                                    creationTimeMs,
                                    currentTrialId,
                                    getSelectedExperiment().getDisplayTitle(context));

                            ensureUnarchived(getSelectedExperiment(), dataController);
                            recorderService.beginServiceRecording(
                                recording.getExperimentName(), resumeIntent);

                            for (StatefulRecorder recorder : recorders.values()) {
                              recorder.startRecording(recording.getRunId());
                            }
                            setRecording(recording);
                            recordingStateChangeInProgress = false;
                            emitter.onComplete();
                          }

                          @Override
                          public void fail(Exception e) {
                            super.fail(e);
                            recordingStateChangeInProgress = false;
                            currentTrialId = "";
                            recordingStatus.onNext(RecordingStatus.INACTIVE);
                            emitter.onError(
                                new RecordingStartFailedException(
                                    RecorderController.ERROR_START_FAILED, e));
                          }
                        });
                  }
                }));
  }

  private RecordingMetadata getRecording() {
    return recordingStatus.getValue().currentRecording;
  }

  private void setRecording(RecordingMetadata recording) {
    recordingStatus.onNext(
        recording == null ? RecordingStatus.INACTIVE : RecordingStatus.active(recording));
  }

  private Completable stopRecordingError(int error) {
    // Still recording, because stopping has failed. Reset the recording status.
    recordingStatus.onNext(recordingStatus.getValue().withState(RecordingState.ACTIVE));
    return Completable.error(new RecordingStopFailedException(error, null));
  }

  @Override
  public Completable stopRecording(final SensorRegistry sensorRegistry) {
    if (!isRecording() || getSelectedExperiment() == null || recordingStateChangeInProgress) {
      return Completable.complete();
    }

    // Disable the record button to stop double-clicks.
    recordingStatus.onNext(recordingStatus.getValue().withState(RecordingState.STOPPING));

    // TODO: What happens when recording stop fails and the app is in the background?

    // First check that all sensors have at least one data point. A recording with no
    // data is invalid.
    for (String sensorId : recorders.keySet()) {
      // If we try to stop recording when a sensor is not connected, recording stop fails.
      if (!registry.isSourceConnectedWithoutError(sensorId)) {
        return stopRecordingError(RecorderController.ERROR_STOP_FAILED_DISCONNECTED);
      }
      // Check to see if it has no data recorded, as this also fails stop recording.
      if (!recorders.get(sensorId).hasRecordedData()) {
        return stopRecordingError(RecorderController.ERROR_STOP_FAILED_NO_DATA);
      }
    }
    recordingStateChangeInProgress = true;
    final boolean activityInForeground = this.activityInForeground;

    return Completable.create(
        emitter ->
            withBoundRecorderService(
                new FallibleConsumer<IRecorderService>() {
                  @Override
                  public void take(final IRecorderService recorderService) throws RemoteException {
                    final Trial trial = getSelectedExperiment().getTrial(currentTrialId);
                    final List<GoosciSensorLayout.SensorLayout> sensorLayoutsAtStop =
                        buildSensorLayouts();
                    if (sensorLayoutsAtStop.size() > 0) {
                      trial.setSensorLayouts(sensorLayoutsAtStop);
                    }
                    trial.setRecordingEndTime(clock.getNow());
                    dataController.updateExperiment(
                        getSelectedExperiment().getExperimentId(),
                        new LoggingConsumer<Success>(TAG, "stopTrial") {
                          @Override
                          public void success(Success value) {
                            for (StatefulRecorder recorder : recorders.values()) {
                              recorder.stopRecording(trial);
                            }
                            trackStopRecording(
                                context.getApplicationContext(),
                                trial,
                                sensorLayoutsAtStop,
                                sensorRegistry);
                            dataController.updateExperiment(
                                getSelectedExperiment().getExperimentId(),
                                endRecordingConsumer(
                                    recorderService, activityInForeground, currentTrialId));

                            // Now actually stop the recording.
                            currentTrialId = "";
                            cleanUpUnusedRecorders();
                            setRecording(null);
                            recordingStateChangeInProgress = false;
                            emitter.onComplete();
                          }

                          @Override
                          public void fail(Exception e) {
                            super.fail(e);
                            recordingStateChangeInProgress = false;
                            setRecording(null);
                            emitter.onError(
                                new RecordingStopFailedException(
                                    RecorderController.ERROR_FAILED_SAVE_RECORDING, e));
                          }
                        });
                  }
                }));
  }

  /** This is a convenience function for testing. */
  @VisibleForTesting
  public Maybe<String> generateSnapshotText(List<String> sensorIds, SensorRegistry sensorRegistry) {
    // TODO: we probably want to do something more structured eventually;
    // this is a placeholder, so we're not doing internationalization, etc.

    return generateSnapshotLabelValue(sensorIds, sensorRegistry)

        // turn the snapshots into strings
        .flatMapObservable(this::textsForSnapshotLabelValue)

        // join them with commas
        .reduce((a, b) -> a + ", " + b)

        // or return a default if there are none
        .defaultIfEmpty("No sensors observed");
  }

  @Override
  public Single<GoosciSnapshotValue.SnapshotLabelValue> generateSnapshotLabelValue(
      List<String> sensorIds, SensorRegistry sensorRegistry) {
    // for each sensorId
    return Observable.fromIterable(sensorIds)

        // get a snapshot from the latest value*
        .flatMapMaybe(sensorId -> makeSnapshot(sensorId, sensorRegistry))

        // gather those into a list
        .toList()

        // And build them into the appropriate proto value
        .map(this::buildSnapshotLabelValue);

    // * The "flat" in flatMapMaybe means that we're taking a stream of Maybes (one for each
    //   sensorId), and "flattening" them into a stream of Strings (that is, we're waiting for
    //   each Maybe to be resolved to its actual snapshotText).
  }

  private MaybeSource<GoosciSnapshotValue.SnapshotLabelValue.SensorSnapshot> makeSnapshot(
      String sensorId, SensorRegistry sensorRegistry) throws Exception {
    BehaviorSubject<ScalarReading> subject = latestValues.get(sensorId);
    if (subject == null) {
      return Maybe.empty();
    }
    final GoosciSensorSpec.SensorSpec spec = getSensorSpec(sensorId, sensorRegistry);
    return subject.firstElement().map(value -> generateSnapshot(spec, value));
  }

  private GoosciSnapshotValue.SnapshotLabelValue buildSnapshotLabelValue(
      List<GoosciSnapshotValue.SnapshotLabelValue.SensorSnapshot> snapshots) {
    GoosciSnapshotValue.SnapshotLabelValue value = new GoosciSnapshotValue.SnapshotLabelValue();
    value.snapshots =
        snapshots.toArray(
            new GoosciSnapshotValue.SnapshotLabelValue.SensorSnapshot[snapshots.size()]);
    return value;
  }

  private Observable<String> textsForSnapshotLabelValue(
      GoosciSnapshotValue.SnapshotLabelValue value) {
    return Observable.fromArray(value.snapshots).map(this::textForSnapshot);
  }

  @NonNull
  private String textForSnapshot(GoosciSnapshotValue.SnapshotLabelValue.SensorSnapshot snapshot) {
    return snapshot.sensor.rememberedAppearance.name + " has value " + snapshot.value;
  }

  @NonNull
  private GoosciSnapshotValue.SnapshotLabelValue.SensorSnapshot generateSnapshot(
      GoosciSensorSpec.SensorSpec spec, ScalarReading reading) {
    GoosciSnapshotValue.SnapshotLabelValue.SensorSnapshot snapshot =
        new GoosciSnapshotValue.SnapshotLabelValue.SensorSnapshot();
    snapshot.sensor = spec;
    snapshot.value = reading.getValue();
    snapshot.timestampMs = reading.getCollectedTimeMillis();
    return snapshot;
  }

  @Override
  public void stopRecordingWithoutSaving() {
    // TODO: Delete partially recorded data and trial?
    if (!isRecording() || recordingStateChangeInProgress) {
      return;
    }
    currentTrialId = "";
    for (StatefulRecorder recorder : recorders.values()) {
      // No trial to update, since we are not saving this.
      recorder.stopRecording(null);
    }
    recordingStateChangeInProgress = true;
    withBoundRecorderService(
        recorderService -> {
          recorderService.endServiceRecording(
              false, "", getSelectedExperiment().getExperimentId(), "");
          recordingStateChangeInProgress = false;
        });
    cleanUpUnusedRecorders();
    setRecording(null);
  }

  @VisibleForTesting
  void trackStopRecording(
      Context context,
      Trial completeTrial,
      List<GoosciSensorLayout.SensorLayout> sensorLayouts,
      SensorRegistry sensorRegistry) {
    // Record how long this session was.
    WhistlePunkApplication.getUsageTracker(context)
        .trackEvent(
            TrackerConstants.CATEGORY_RUNS,
            TrackerConstants.ACTION_CREATE,
            null,
            completeTrial.getOriginalLastTimestamp() - getRecording().getStartTime());

    // Record which sensors were recorded and information about their layouts.
    List<String> sensorLogs = new ArrayList<>();
    for (GoosciSensorLayout.SensorLayout layout : sensorLayouts) {
      String loggingId = sensorRegistry.getLoggingId(layout.sensorId);
      sensorLogs.add(getLayoutLoggingString(loggingId, layout));
    }
    WhistlePunkApplication.getUsageTracker(context)
        .trackEvent(
            TrackerConstants.CATEGORY_RUNS,
            TrackerConstants.ACTION_RECORDED,
            Joiner.on(",").join(sensorLogs),
            0);
  }

  String getLayoutLoggingString(String loggingId, GoosciSensorLayout.SensorLayout layout) {
    StringBuilder builder = new StringBuilder();
    builder.append(loggingId);
    builder.append("|");
    builder.append(
        layout.cardView == GoosciSensorLayout.SensorLayout.CardView.METER ? "meter" : "graph");
    builder.append("|");
    builder.append(layout.audioEnabled ? "audioOn" : "audioOff");
    return builder.toString();
  }

  /**
   * Convenience function for asynchronously binding to the service and doing something with it.
   *
   * @param c what to do
   */
  protected void withBoundRecorderService(final FallibleConsumer<IRecorderService> c) {
    // TODO: push logic to RecorderService
    if (serviceConnection == null) {
      serviceConnection = connectionSupplier.get();
    }
    serviceConnection.runWithService(c);
  }

  public int addTriggerFiredListener(TriggerFiredListener listener) {
    Preconditions.checkNotNull(listener);
    int listenerId = nextTriggerListenerId++;
    triggerListeners.put(listenerId, listener);
    return listenerId;
  }

  @Override
  public void removeTriggerFiredListener(int listenerId) {
    triggerListeners.remove(listenerId);
  }

  @Override
  public void addObservedIdsListener(String listenerId, ObservedIdsListener listener) {
    observedIdListeners.put(listenerId, listener);
    listener.onObservedIdsChanged(getMostRecentObservedSensorIds());
  }

  @Override
  public void removeObservedIdsListener(String listenerId) {
    observedIdListeners.remove(listenerId);
  }

  @Override
  public void setSelectedExperiment(Experiment experiment) {
    // TODO: pass in observable instead?
    selectedExperiment.onNext(experiment);
  }

  private boolean isRecording() {
    return getRecording() != null;
  }

  @VisibleForTesting
  public Map<String, StatefulRecorder> getRecorders() {
    return recorders;
  }

  @VisibleForTesting
  void ensureUnarchived(Experiment experiment, DataController dc) {
    RecordFragment.ensureUnarchived(context, experiment, dc);
  }

  @Override
  public void setRecordActivityInForeground(boolean isInForeground) {
    activityInForeground = isInForeground;
  }

  private MaybeConsumer<Success> endRecordingConsumer(
      IRecorderService recorderService, boolean activityInForeground, String trialId) {
    return new LoggingConsumer<Success>(TAG, "update completed trial") {
      @Override
      public void success(Success value) {
        // TODO: Can we use SimpleMetadataManager#close instead?
        dataController.saveImmediately(
            new LoggingConsumer<Success>(TAG, "save immediately") {
              @Override
              public void success(Success value) {
                // Close the service. When the service is
                // closed, if the app is in the background,
                // all processes will stop -- so this needs
                // to be the last thing to happen!
                Experiment exp = getSelectedExperiment();
                recorderService.endServiceRecording(
                    !activityInForeground,
                    trialId,
                    exp.getExperimentId(),
                    exp.getDisplayTitle(context));
              }
            });
      }
    };
  }
}
