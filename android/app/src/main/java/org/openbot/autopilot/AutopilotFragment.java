package org.openbot.autopilot;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.camera.core.ImageProxy;
import androidx.navigation.Navigation;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.nononsenseapps.filepicker.Utils;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;
import org.openbot.R;
import org.openbot.common.CameraFragment;
import org.openbot.databinding.FragmentAutopilotBinding;
import org.openbot.env.BorderedText;
import org.openbot.env.Control;
import org.openbot.env.ImageUtils;
import org.openbot.server.ServerCommunication;
import org.openbot.server.ServerListener;
import org.openbot.tflite.Autopilot;
import org.openbot.tflite.Model;
import org.openbot.tflite.Network;
import org.openbot.tracking.MultiBoxTracker;
import org.openbot.utils.Constants;
import org.openbot.utils.Enums;
import org.openbot.utils.FileUtils;
import org.openbot.utils.PermissionUtils;
import timber.log.Timber;

public class AutopilotFragment extends CameraFragment implements ServerListener {

  // options for drop down in object nav?
  private FragmentAutopilotBinding binding;
  private Handler handler;
  private HandlerThread handlerThread;
  private ServerCommunication serverCommunication;

  private long lastProcessingTimeMs;
  private boolean computingNetwork = false;

  private static final float TEXT_SIZE_DIP = 10;

  private Autopilot autopilot;

  private Matrix frameToCropTransform;
  private Bitmap croppedBitmap;
  private int sensorOrientation;

  private MultiBoxTracker tracker;

  private Model model = Model.Autopilot_F;
  private Network.Device device = Network.Device.CPU;
  private int numThreads = -1;

  private ArrayAdapter<CharSequence> modelAdapter;
  private ActivityResultLauncher<Intent> mStartForResult;
  private int selectedModelIndex = 0;

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    mStartForResult =
        registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
              if (result.getResultCode() == Activity.RESULT_OK) {

                Intent intent = result.getData();
                // Handle the Intent
                List<Uri> files = Utils.getSelectedFilesFromResult(intent);

                String fileName = new File(files.get(0).getPath()).getName();
                if (FileUtils.checkFileExistence(requireActivity(), fileName)) {
                  AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity());
                  builder.setTitle(R.string.file_available_title);
                  builder.setMessage(R.string.file_available_body);
                  builder.setPositiveButton(
                      "Yes", (dialog, id) -> processModelFromStorage(files, fileName));
                  builder.setNegativeButton(
                      "Cancel",
                      (dialog, id) -> {
                        // User cancelled the dialog
                      });
                  AlertDialog dialog = builder.create();
                  dialog.show();
                } else {
                  processModelFromStorage(files, fileName);
                }
              }
            });
  }

  private void processModelFromStorage(List<Uri> files, String fileName) {
    try {
      InputStream inputStream =
          requireActivity().getContentResolver().openInputStream(files.get(0));
      FileUtils.copyFile(inputStream, fileName, requireActivity().getFilesDir().getAbsolutePath());
    } catch (IOException e) {
      e.printStackTrace();
    }

    modelAdapter.clear();
    modelAdapter.addAll(Arrays.asList(getResources().getTextArray(R.array.autopilot_models)));
    modelAdapter.addAll(getModelFiles());
    modelAdapter.add("Choose From Device");
    modelAdapter.notifyDataSetChanged();
    binding.modelSpinner.setSelection(modelAdapter.getPosition(fileName));
    setModel(
        new Model(
            1,
            Model.CLASS.AUTOPILOT_F,
            Model.TYPE.AUTOPILOT,
            fileName,
            Model.PATH_TYPE.FILE,
            fileName,
            new Size(256, 96)));

    Toast.makeText(
            requireContext().getApplicationContext(),
            "AutoPilotModel added: " + model,
            Toast.LENGTH_SHORT)
        .show();
  }

  @Override
  public View onCreateView(
      @NotNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    // Inflate the layout for this fragment
    binding = FragmentAutopilotBinding.inflate(inflater, container, false);

    return inflateFragment(binding, inflater, container);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    binding.controllerContainer.speedInfo.setText(getString(R.string.speedInfo, "---,---"));

    binding.deviceSpinner.setSelection(preferencesManager.getDevice());
    setNumThreads(preferencesManager.getNumThreads());

    binding.cameraToggle.setOnClickListener(v -> toggleCamera());

    List<CharSequence> models =
        Arrays.asList(getResources().getTextArray(R.array.autopilot_models));
    modelAdapter =
        new ArrayAdapter<>(requireContext(), R.layout.spinner_item, new ArrayList<>(models));
    modelAdapter.addAll(getModelFiles());
    modelAdapter.add("Choose From Device");
    modelAdapter.setDropDownViewResource(android.R.layout.simple_dropdown_item_1line);
    binding.modelSpinner.setAdapter(modelAdapter);

    setAnalyserResolution(Enums.Preview.HD.getValue());
    binding.modelSpinner.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          @Override
          public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            String selected = parent.getItemAtPosition(position).toString();
            if (selected.equals("Choose From Device")) {
              binding.modelSpinner.setSelection(selectedModelIndex);
              openPicker();
            } else
              try {
                setModel(Model.fromId(selected.toUpperCase()));
              } catch (IllegalArgumentException e) {
                setModel(
                    new Model(
                        1,
                        Model.CLASS.AUTOPILOT_F,
                        Model.TYPE.AUTOPILOT,
                        selected,
                        Model.PATH_TYPE.FILE,
                        selected,
                        new Size(256, 96)));
              }
            selectedModelIndex = position;
          }

          @Override
          public void onNothingSelected(AdapterView<?> parent) {}
        });
    binding.deviceSpinner.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          @Override
          public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            String selected = parent.getItemAtPosition(position).toString();
            setDevice(Network.Device.valueOf(selected.toUpperCase()));
          }

          @Override
          public void onNothingSelected(AdapterView<?> parent) {}
        });

    binding.plus.setOnClickListener(
        v -> {
          String threads = binding.threads.getText().toString().trim();
          int numThreads = Integer.parseInt(threads);
          if (numThreads >= 9) return;
          setNumThreads(++numThreads);
          binding.threads.setText(String.valueOf(numThreads));
        });
    binding.minus.setOnClickListener(
        v -> {
          String threads = binding.threads.getText().toString().trim();
          int numThreads = Integer.parseInt(threads);
          if (numThreads == 1) return;
          setNumThreads(--numThreads);
          binding.threads.setText(String.valueOf(numThreads));
        });
    BottomSheetBehavior.from(binding.aiBottomSheet).setState(BottomSheetBehavior.STATE_EXPANDED);

    mViewModel
        .getUsbStatus()
        .observe(getViewLifecycleOwner(), status -> binding.usbToggle.setChecked(status));

    binding.usbToggle.setChecked(vehicle.isUsbConnected());

    binding.usbToggle.setOnClickListener(
        v -> {
          binding.usbToggle.setChecked(vehicle.isUsbConnected());
          Navigation.findNavController(requireView()).navigate(R.id.open_settings_fragment);
        });

    setSpeedMode(Enums.SpeedMode.getByID(preferencesManager.getSpeedMode()));
    setControlMode(Enums.ControlMode.getByID(preferencesManager.getControlMode()));
    setDriveMode(Enums.DriveMode.getByID(preferencesManager.getDriveMode()));

    binding.controllerContainer.controlMode.setOnClickListener(
        v -> {
          Enums.ControlMode controlMode =
              Enums.ControlMode.getByID(preferencesManager.getControlMode());
          if (controlMode != null) setControlMode(Enums.switchControlMode(controlMode));
        });
    binding.controllerContainer.driveMode.setOnClickListener(
        v -> setDriveMode(Enums.switchDriveMode(vehicle.getDriveMode())));

    binding.controllerContainer.speedMode.setOnClickListener(
        v ->
            setSpeedMode(
                Enums.toggleSpeed(
                    Enums.Direction.CYCLIC.getValue(),
                    Enums.SpeedMode.getByID(preferencesManager.getSpeedMode()))));

    binding.autoSwitch.setOnClickListener(v -> setNetworkEnabled(binding.autoSwitch.isChecked()));
  }

  private void openPicker() {

    Intent i = new Intent(requireActivity(), BackHandlingFilePickerActivity.class);
    // This works if you defined the intent filter
    // Intent i = new Intent(Intent.ACTION_GET_CONTENT);

    // Set these depending on your use case. These are the defaults.
    i.putExtra(BackHandlingFilePickerActivity.EXTRA_ALLOW_MULTIPLE, false);
    i.putExtra(BackHandlingFilePickerActivity.EXTRA_ALLOW_CREATE_DIR, false);
    i.putExtra(BackHandlingFilePickerActivity.EXTRA_MODE, BackHandlingFilePickerActivity.MODE_FILE);

    // Configure initial directory by specifying a String.
    // You could specify a String like "/storage/emulated/0/", but that can
    // dangerous. Always use Android's API calls to get paths to the SD-card or
    // internal memory.
    i.putExtra(
        BackHandlingFilePickerActivity.EXTRA_START_PATH,
        Environment.getExternalStorageDirectory().getPath());

    mStartForResult.launch(i);
  }

  private void updateCropImageInfo() {
    //    Timber.i("%s x %s",getPreviewSize().getWidth(), getPreviewSize().getHeight());
    //    Timber.i("%s x %s",getMaxAnalyseImageSize().getWidth(),
    // getMaxAnalyseImageSize().getHeight());
    frameToCropTransform = null;

    sensorOrientation = 90 - ImageUtils.getScreenOrientation(requireActivity());

    final float textSizePx =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
    BorderedText borderedText = new BorderedText(textSizePx);
    borderedText.setTypeface(Typeface.MONOSPACE);

    tracker = new MultiBoxTracker(requireContext());

    Timber.i("Camera orientation relative to screen canvas: %d", sensorOrientation);

    recreateNetwork(getModel(), getDevice(), getNumThreads());
    if (autopilot == null) {
      Timber.e("No network on preview!");
      return;
    }

    binding.trackingOverlay.addCallback(
        canvas -> {
          tracker.draw(canvas);
          //          tracker.drawDebug(canvas);
        });
    tracker.setFrameConfiguration(
        getMaxAnalyseImageSize().getWidth(),
        getMaxAnalyseImageSize().getHeight(),
        sensorOrientation);
  }

  protected void onInferenceConfigurationChanged() {
    computingNetwork = false;
    if (croppedBitmap == null) {
      // Defer creation until we're getting camera frames.
      return;
    }
    final Network.Device device = getDevice();
    final Model model = getModel();
    final int numThreads = getNumThreads();
    runInBackground(() -> recreateNetwork(model, device, numThreads));
  }

  private void recreateNetwork(Model model, Network.Device device, int numThreads) {
    tracker.clearTrackedObjects();
    if (autopilot != null) {
      Timber.d("Closing autoPilot.");
      autopilot.close();
      autopilot = null;
    }

    try {
      Timber.d(
          "Creating autopilot (model=%s, device=%s, numThreads=%d)", model, device, numThreads);
      autopilot = Autopilot.create(requireActivity(), model, device, numThreads);
      croppedBitmap =
          Bitmap.createBitmap(
              autopilot.getImageSizeX(), autopilot.getImageSizeY(), Bitmap.Config.ARGB_8888);
      frameToCropTransform =
          ImageUtils.getTransformationMatrix(
              getMaxAnalyseImageSize().getWidth(),
              getMaxAnalyseImageSize().getHeight(),
              croppedBitmap.getWidth(),
              croppedBitmap.getHeight(),
              sensorOrientation,
              autopilot.getCropRect(),
              autopilot.getMaintainAspect());
      requireActivity()
          .runOnUiThread(
              () ->
                  binding.inputResolution.setText(
                      String.format(
                          "%dx%d", autopilot.getImageSizeX(), autopilot.getImageSizeY())));

      Matrix cropToFrameTransform = new Matrix();
      frameToCropTransform.invert(cropToFrameTransform);

    } catch (IllegalArgumentException | IOException e) {
      String msg = "Failed to create network.";
      Timber.e(e, msg);
      requireActivity()
          .runOnUiThread(
              () ->
                  Toast.makeText(
                          requireContext().getApplicationContext(),
                          e.getMessage(),
                          Toast.LENGTH_LONG)
                      .show());
    }
  }

  @Override
  public synchronized void onResume() {
    serverCommunication = new ServerCommunication(requireContext(), this);
    serverCommunication.start();
    handlerThread = new HandlerThread("inference");
    handlerThread.start();
    handler = new Handler(handlerThread.getLooper());
    super.onResume();
  }

  @Override
  public synchronized void onPause() {
    handlerThread.quitSafely();
    try {
      handlerThread.join();
      handlerThread = null;
      handler = null;
    } catch (final InterruptedException e) {
      e.printStackTrace();
    }
    serverCommunication.stop();
    super.onPause();
  }

  protected synchronized void runInBackground(final Runnable r) {
    if (handler != null) {
      handler.post(r);
    }
  }

  @Override
  protected void processUSBData(String data) {
    binding.controllerContainer.speedInfo.setText(
        getString(
            R.string.speedInfo,
            String.format(
                Locale.US, "%3.0f,%3.0f", vehicle.getLeftWheelRPM(), vehicle.getRightWheelRPM())));
  }

  @Override
  protected void processControllerKeyData(String commandType) {
    switch (commandType) {
      case Constants.CMD_DRIVE:
        binding.controllerContainer.controlInfo.setText(
            String.format(Locale.US, "%.0f,%.0f", vehicle.getLeftSpeed(), vehicle.getRightSpeed()));
        break;

      case Constants.CMD_DRIVE_MODE:
        setDriveMode(Enums.switchDriveMode(vehicle.getDriveMode()));
        break;

      case Constants.CMD_SPEED_DOWN:
        setSpeedMode(
            Enums.toggleSpeed(
                Enums.Direction.DOWN.getValue(),
                Enums.SpeedMode.getByID(preferencesManager.getSpeedMode())));
        break;

      case Constants.CMD_SPEED_UP:
        setSpeedMode(
            Enums.toggleSpeed(
                Enums.Direction.UP.getValue(),
                Enums.SpeedMode.getByID(preferencesManager.getSpeedMode())));
        break;

      case Constants.CMD_NETWORK:
        setNetworkEnabledWithAudio(!binding.autoSwitch.isChecked());
        break;
    }
  }

  private void setNetworkEnabledWithAudio(boolean b) {
    setNetworkEnabled(b);

    if (b) {
      audioPlayer.play(voice, "network_enabled.mp3");
      runInBackground(
          () -> {
            try {
              TimeUnit.MILLISECONDS.sleep(lastProcessingTimeMs);
              vehicle.setControl(0, 0);
              requireActivity()
                  .runOnUiThread(() -> binding.inferenceInfo.setText(R.string.time_fps));
            } catch (InterruptedException e) {
              Timber.e(e, "Got interrupted.");
            }
          });
    } else audioPlayer.playDriveMode(voice, vehicle.getDriveMode());
  }

  private void setNetworkEnabled(boolean b) {
    binding.autoSwitch.setChecked(b);
    binding.controllerContainer.controlMode.setEnabled(!b);
    binding.controllerContainer.driveMode.setEnabled(!b);
    binding.controllerContainer.speedInfo.setEnabled(!b);

    binding.controllerContainer.controlMode.setAlpha(b ? 0.5f : 1f);
    binding.controllerContainer.driveMode.setAlpha(b ? 0.5f : 1f);
    binding.controllerContainer.speedMode.setAlpha(b ? 0.5f : 1f);

    if (!b) handler.postDelayed(() -> vehicle.setControl(0, 0), 500);
  }

  private long frameNum = 0;

  @Override
  protected void processFrame(Bitmap bitmap, ImageProxy image) {
    if (tracker == null) updateCropImageInfo();

    ++frameNum;
    if (binding != null && binding.autoSwitch.isChecked()) {
      // If network is busy, return.
      if (computingNetwork) {
        return;
      }

      computingNetwork = true;
      Timber.i("Putting image " + frameNum + " for detection in bg thread.");

      runInBackground(
          () -> {
            final Canvas canvas = new Canvas(croppedBitmap);
            canvas.drawBitmap(bitmap, frameToCropTransform, null);

            if (autopilot != null) {
              Timber.i("Running autopilot on image %s", frameNum);
              final long startTime = SystemClock.elapsedRealtime();
              handleDriveCommand(autopilot.recognizeImage(croppedBitmap, vehicle.getIndicator()));
              lastProcessingTimeMs = SystemClock.elapsedRealtime() - startTime;
            }

            computingNetwork = false;
          });
      if (lastProcessingTimeMs > 0)
        requireActivity()
            .runOnUiThread(
                () ->
                    binding.inferenceInfo.setText(
                        String.format(Locale.US, "%d fps", 1000 / lastProcessingTimeMs)));
    }
  }

  protected void handleDriveCommand(Control control) {
    vehicle.setControl(control);
    float left = vehicle.getLeftSpeed();
    float right = vehicle.getRightSpeed();
    binding.controllerContainer.controlInfo.setText(
        String.format(Locale.US, "%.0f,%.0f", left, right));
  }

  @Override
  public void onConnectionEstablished(String ipAddress) {}

  @Override
  public void onAddModel(String model) {
    if (modelAdapter != null && modelAdapter.getPosition(model) == -1) {
      modelAdapter.add(model);
    } else {
      if (model.equals(binding.modelSpinner.getSelectedItem())) {
        setModel(
            new Model(
                1,
                Model.CLASS.AUTOPILOT_F,
                Model.TYPE.AUTOPILOT,
                model,
                Model.PATH_TYPE.FILE,
                model,
                new Size(256, 96)));
      }
    }
    Toast.makeText(
            requireContext().getApplicationContext(),
            "AutoPilotModel added: " + model,
            Toast.LENGTH_SHORT)
        .show();
  }

  @Override
  public void onRemoveModel(String model) {
    if (modelAdapter != null && modelAdapter.getPosition(model) != -1) {
      modelAdapter.remove(model);
    }
    Toast.makeText(
            requireContext().getApplicationContext(),
            "AutoPilotModel removed: " + model,
            Toast.LENGTH_SHORT)
        .show();
  }

  protected Model getModel() {
    return model;
  }

  private void setModel(Model model) {
    if (this.model != model) {
      Timber.d("Updating  model: %s", model);
      this.model = model;
      preferencesManager.setAutoPilotModel(model.toString());
      onInferenceConfigurationChanged();
    }
  }

  protected Network.Device getDevice() {
    return device;
  }

  private void setDevice(Network.Device device) {
    if (this.device != device) {
      Timber.d("Updating  device: %s", device);
      this.device = device;
      final boolean threadsEnabled = device == Network.Device.CPU;
      binding.plus.setEnabled(threadsEnabled);
      binding.minus.setEnabled(threadsEnabled);
      binding.threads.setText(threadsEnabled ? String.valueOf(numThreads) : "N/A");
      if (threadsEnabled) binding.threads.setTextColor(Color.BLACK);
      else binding.threads.setTextColor(Color.GRAY);
      preferencesManager.setDevice(device.ordinal());
      onInferenceConfigurationChanged();
    }
  }

  protected int getNumThreads() {
    return numThreads;
  }

  private void setNumThreads(int numThreads) {
    if (this.numThreads != numThreads) {
      Timber.d("Updating  numThreads: %s", numThreads);
      this.numThreads = numThreads;
      preferencesManager.setNumThreads(numThreads);
      onInferenceConfigurationChanged();
    }
  }

  private String[] getModelFiles() {
    return requireActivity().getFilesDir().list((dir1, name) -> name.endsWith(".tflite"));
  }

  private void setSpeedMode(Enums.SpeedMode speedMode) {
    if (speedMode != null) {
      switch (speedMode) {
        case SLOW:
          binding.controllerContainer.speedMode.setImageResource(R.drawable.ic_speed_low);
          break;
        case NORMAL:
          binding.controllerContainer.speedMode.setImageResource(R.drawable.ic_speed_medium);
          break;
        case FAST:
          binding.controllerContainer.speedMode.setImageResource(R.drawable.ic_speed_high);
          break;
      }

      Timber.d("Updating  controlSpeed: %s", speedMode);
      preferencesManager.setSpeedMode(speedMode.getValue());
      vehicle.setSpeedMultiplier(speedMode.getValue());
    }
  }

  private void setControlMode(Enums.ControlMode controlMode) {
    if (controlMode != null) {
      switch (controlMode) {
        case GAMEPAD:
          binding.controllerContainer.controlMode.setImageResource(R.drawable.ic_controller);
          disconnectPhoneController();
          break;
        case PHONE:
          binding.controllerContainer.controlMode.setImageResource(R.drawable.ic_phone);
          if (!PermissionUtils.hasPermission(requireContext(), Constants.PERMISSION_LOCATION))
            PermissionUtils.requestPermissions(
                this,
                new String[] {Constants.PERMISSION_LOCATION},
                Constants.REQUEST_LOCATION_PERMISSION_CONTROLLER);
          else connectPhoneController();

          break;
      }
      Timber.d("Updating  controlMode: %s", controlMode);
      preferencesManager.setControlMode(controlMode.getValue());
    }
  }

  protected void setDriveMode(Enums.DriveMode driveMode) {
    if (driveMode != null) {
      switch (driveMode) {
        case DUAL:
          binding.controllerContainer.driveMode.setImageResource(R.drawable.ic_dual);
          break;
        case GAME:
          binding.controllerContainer.driveMode.setImageResource(R.drawable.ic_game);
          break;
        case JOYSTICK:
          binding.controllerContainer.driveMode.setImageResource(R.drawable.ic_joystick);
          break;
      }

      Timber.d("Updating  driveMode: %s", driveMode);
      vehicle.setDriveMode(driveMode);
      preferencesManager.setDriveMode(driveMode.getValue());
    }
  }

  private void connectPhoneController() {
    phoneController.connect(requireContext());
    Enums.DriveMode oldDriveMode = currentDriveMode;
    // Currently only dual drive mode supported
    setDriveMode(Enums.DriveMode.DUAL);
    binding.controllerContainer.driveMode.setAlpha(0.5f);
    binding.controllerContainer.driveMode.setEnabled(false);
    preferencesManager.setDriveMode(oldDriveMode.getValue());
  }

  private void disconnectPhoneController() {
    phoneController.disconnect();
    setDriveMode(Enums.DriveMode.getByID(preferencesManager.getDriveMode()));
    binding.controllerContainer.driveMode.setEnabled(true);
    binding.controllerContainer.driveMode.setAlpha(1.0f);
  }
}
