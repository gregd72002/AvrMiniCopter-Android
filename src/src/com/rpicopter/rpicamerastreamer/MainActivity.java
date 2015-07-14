package com.rpicopter.rpicamerastreamer;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import org.freedesktop.gstreamer.GStreamer;

import com.rpicopter.rpicamerastreamer.util.InfoBox;
import com.rpicopter.rpicamerastreamer.util.LinkQuality;
import com.rpicopter.rpicamerastreamer.util.RPiCam;
import com.rpicopter.rpicamerastreamer.util.RPiController;
import com.rpicopter.rpicamerastreamer.util.SystemUiHider;
import com.rpicopter.rpicamerastreamer.util.Utils;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 *
 * @see SystemUiHider
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback, Callback  {
	private String message;
    private native void nativeInit();     // Initialize native code, build pipeline, etc
    private native void nativeConfig(String pipeline);
    private native void nativeFinalize(); // Destroy pipeline and shutdown native code
    private native void nativeStart();     // Constructs PIPELINE
    private native void nativeStop();     // Destroys PIPELINE
    private native void nativePlay();     // Set pipeline to PLAYING
    private native void nativePause();    // Set pipeline to PAUSED
    private native void nativeSurfaceInit(Object surface);
    private native void nativeSurfaceFinalize();
    private native static boolean nativeClassInit(); // Initialize native class: cache Method IDs for callbacks
    private long native_custom_data;      // Native code will use this to keep private data
    private int uid;
    private long lastProbe,lastBytes;
    private float currentSpeed;
    private InfoBox infobox;
    private boolean split_screen = false;
    //private GameController gc;
    private boolean pipeline_started;
    private boolean is_running;
    
    private RPiController rpi;
    private RPiCam rpicam;
    
    private LinkQuality lq;
    
    /* GameController */
    private int gcdevid;
    private int y_max,t_max,t_min,pr_max;
    private int stream_type;
	/**
	 * Whether or not the system UI should be auto-hidden after
	 * {@link #AUTO_HIDE_DELAY_MILLIS} milliseconds.
	 */
	private static final boolean AUTO_HIDE = true;

	/**
	 * If {@link #AUTO_HIDE} is set, the number of milliseconds to wait after
	 * user interaction before hiding the system UI.
	 */
	private static final int AUTO_HIDE_DELAY_MILLIS = 3000;

	/**
	 * If set, will toggle the system UI visibility upon interaction. Otherwise,
	 * will show the system UI visibility upon interaction.
	 */
	private static final boolean TOGGLE_ON_CLICK = true;

	/**
	 * The flags to pass to {@link SystemUiHider#getInstance}.
	 */
	private static final int HIDER_FLAGS = SystemUiHider.FLAG_HIDE_NAVIGATION;

	/**
	 * The instance of the {@link SystemUiHider} for this activity.
	 */
	private SystemUiHider mSystemUiHider;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		infobox = new InfoBox();
		message = new String();
		pipeline_started = false;
		is_running = false;
		
		lq = new LinkQuality(this);
		uid = android.os.Process.myUid();
		lastProbe = System.currentTimeMillis();
		lastBytes = TrafficStats.getUidRxBytes(uid) + TrafficStats.getUidTxBytes(uid);
        // Initialize GStreamer and warn if it fails
        try {
            GStreamer.init(this);
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            finish(); 
            return;
        }
        
		setContentView(R.layout.activity_main);

		//final FrameLayout fr = (FrameLayout) this.findViewById(R.id.main_frame_layout);
		//fr.setForeground(null);
		final TextView tv = (TextView) this.findViewById(R.id.textview_message);
		tv.setVisibility(View.INVISIBLE);
		final View controlsViewTop = findViewById(R.id.fullscreen_content_controls_top);
		final View controlsViewBottom = findViewById(R.id.fullscreen_content_controls_bottom);
		//final View contentView = findViewById(R.id.surface_video);
		
        SurfaceView sv = (SurfaceView) this.findViewById(R.id.surface_video);
        SurfaceHolder sh = sv.getHolder();
        sh.addCallback(this);

		// Set up an instance of SystemUiHider to control the system UI for
		// this activity.
        
  
		mSystemUiHider = SystemUiHider.getInstance(this, sv,
				HIDER_FLAGS);
		mSystemUiHider.setup();
	
		mSystemUiHider
				.setOnVisibilityChangeListener(new SystemUiHider.OnVisibilityChangeListener() {

					@Override
					@TargetApi(Build.VERSION_CODES.HONEYCOMB_MR2)
					public void onVisibilityChange(boolean visible) {
							controlsViewTop.setVisibility(visible ? View.VISIBLE
									: View.GONE);
							controlsViewBottom.setVisibility(visible ? View.VISIBLE
									: View.GONE);
							
						if (visible && AUTO_HIDE) {
							// Schedule a hide().
							delayedHide(AUTO_HIDE_DELAY_MILLIS);
						}
					}
				});

		// Set up the user interaction to manually show or hide the system UI.
		sv.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) { //runs only on hide
				clearMsg(100);
				if (TOGGLE_ON_CLICK) {
					mSystemUiHider.toggle();
				} else {
					mSystemUiHider.show();
				}
			}
		});

		// Upon interacting with UI controls, delay any scheduled hide()
		// operations to prevent the jarring behavior of controls going away
		// while interacting with the UI.
		findViewById(R.id.bottom_button).setOnTouchListener(
				mDelayHideTouchListener);
		
		SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
		Editor editor = sharedPrefs.edit();
		
		String my_ip = Utils.getIPAddress(true);
		if (my_ip=="") my_ip = "127.0.0.1";
		editor.putString("my_ip", my_ip);
		
		if (sharedPrefs.getString("my_port", "")=="")
			editor.putString("my_port", "8888");
		
		String rpi_ip_s = sharedPrefs.getString("rpi_ip", "");
		try {
			Utils.ip_s2i(rpi_ip_s);
		} catch (NumberFormatException ex) {
			byte []ip = Utils.ip_i2b(lq.getGW());
			editor.putString("rpi_ip", Utils.ip_b2s(ip));
		}
			
		
		//editor.putString("rpi_ip", "10.0.2.1");

		if (sharedPrefs.getString("rpi_port", "")=="")
			editor.putString("rpi_port", "1030");
		
		if (sharedPrefs.getString("rpi_cport", "")=="")
			editor.putString("rpi_cport", "1035");

		if (sharedPrefs.getString("t_min", "")=="")
			editor.putString("t_min", "1000");
		
		if (sharedPrefs.getString("t_max", "")=="")
			editor.putString("t_max", "1600");
		if (sharedPrefs.getString("y_max", "")=="")
			editor.putString("y_max", "45");
		if (sharedPrefs.getString("pr_max", "")=="")
			editor.putString("pr_max", "45");

		if (sharedPrefs.getString("stream_type", "")=="")
			editor.putString("stream_type", "0");

		editor.commit();
	
		initializePlayer();

		nativeInit();
		
		updateUI();
		
		initGC();
		
		return;
	}

	@Override
	protected void onPostCreate(Bundle savedInstanceState) {
		super.onPostCreate(savedInstanceState);

		// Trigger the initial hide() shortly after the activity has been
		// created, to briefly hint to the user that UI controls
		// are available.
		delayedHide(100);
	}
	
	public void clickMsg(View v) {
		message = "";
		_updateUI();
	}
	
	public void clickOptions(View v) {
		  message = "";
		  stopStream();
		  Intent i = new Intent(getApplicationContext(), SettingsActivity.class);
          startActivityForResult(i, 1);
	}

	private void startStream() {
		rpicam.start();
		if (!pipeline_started) nativeStart();
		is_running = true;
	}
	
	private void stopStream() {
		  if (pipeline_started) nativeStop();
		  if (rpicam!=null) rpicam.stop();
		  is_running = false;
	}

	
	public void clickStart(View v) {
		message = "";
		if (rpicam == null) {
			setMessage("Configuration error?");
			return;
		}
		if (is_running) //we are currently playing
			stopStream();
		else { //we are currently not playing
			startStream();
		}
		_updateUI();
	}
	
	private void setError(final int type, final String _message) {
		Log.d("setError","setError: "+type+" "+_message);
		
		if (type==1) {
			pipeline_started = false;
			stopStream();
		}
    	message = _message;
    	updateUI();	
	}
	
    // Called from native code. This sets the content of the TextView from the UI thread.
    private void setMessage(final String _message) {
    	message = _message;
    	updateUI();
    }
    
    private void notifyState(final int _state) {
    	Log.d("STATE","STATE "+_state);
    	switch (_state) {
    		case 0: is_running = false; pipeline_started = false; break;
    		case 1: is_running = false; break;
    		case 4: pipeline_started = true;
    	}
    	updateUI();
    }
    
    private void initializePlayer() {
    	SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
    	String my_ip_s = sharedPrefs.getString("my_ip", "");
    	String my_p_s = sharedPrefs.getString("my_port", "");
    	String rpi_ip_s = sharedPrefs.getString("rpi_ip", "");
    	String rpi_p_s = sharedPrefs.getString("rpi_port", "");
    	String rpi_cp_s = sharedPrefs.getString("rpi_cport", "");
    	String stream_type_s = sharedPrefs.getString("stream_type", "");
    	split_screen = sharedPrefs.getBoolean("split_screen",false);
    	String t_max_s = sharedPrefs.getString("t_max", "");
    	String t_min_s = sharedPrefs.getString("t_min", "");
    	String y_max_s = sharedPrefs.getString("y_max", "");
    	String pr_max_s = sharedPrefs.getString("pr_max", "");
    	int my_p;
    	byte [] my_ip;
    	int rpi_p,rpi_cp;
    	byte [] rpi_ip;
    	try {
    		stream_type = Integer.parseInt(stream_type_s);
    		t_max = Integer.parseInt(t_max_s);
    		t_min = Integer.parseInt(t_min_s);
    		y_max = Integer.parseInt(y_max_s);
    		pr_max = Integer.parseInt(pr_max_s);
    	} catch (Exception ex) {
    		setMessage("Error parsing config.");
    		Log.d("initializePlayer1","initializePlayer1"+ex);
    		return;
    	}
    	try{
    		my_p = Integer.parseInt(my_p_s);
    		my_ip = Utils.ip_s2b(my_ip_s);
    		rpi_cp = Integer.parseInt(rpi_cp_s);
    		rpi_p = Integer.parseInt(rpi_p_s);
    		rpi_ip = Utils.ip_s2b(rpi_ip_s);
    	} catch (Exception ex) {
    		setMessage("Check preferances for IP address and port!");
    		Log.d("initializePlayer","initializePlayer"+ex);
    		return;
    	}
    	
    	String pipeline = "";
		
		if (split_screen) {
			pipeline = "udpsrc address="+my_ip_s+" port="+my_p_s+" caps=\"application/x-rtp, media=(string)video, clock-rate=(int)90000, encoding-name=(string)H264, framerate=90\" ! rtph264depay  ! decodebin ! tee name=t ! queue ! videomixer name=m sink_0::xpos=0 sink_1::xpos=640 ! autovideosink sync=false t. ! queue ! m.";
		} else {
			pipeline = "udpsrc address="+my_ip_s+" port="+my_p_s+" caps=\"application/x-rtp, media=(string)video, clock-rate=(int)90000, encoding-name=(string)H264, framerate=90\" ! rtph264depay ! decodebin ! autovideosink sync=false";			
		}
		

/*
    	switch (stream_type) {
    		case 1: pipeline = "udpsrc address="+my_ip_s+" port="+my_p_s+" caps=\"application/x-rtp, media=(string)video, clock-rate=(int)90000, encoding-name=(string)H264\" ! rtph264depay  ! avdec_h264 ! tee name=t ! queue ! videomixer name=m sink_0::xpos=0 sink_1::xpos=640 ! autovideosink sync=false t. ! queue ! m.";
    		  	break;

    		case 6: 
    			File dir = this.getFilesDir();
    			//dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
			try {
				String d = dir.getCanonicalPath();
				d = d+"/TESTVIDEO.h264";
				//
				d="/sdcard/TESTVIDEO.h264";
				Log.d("PATH","WRITING TO: "+d);
    			pipeline = "udpsrc address="+my_ip_s+" port="+my_p_s+" caps=\"application/x-rtp, media=(string)video, clock-rate=(int)90000, encoding-name=(string)H264\" ! rtph264depay ! h264parse config-interval=1  ! tee name=t ! queue ! avdec_h264 ! videoconvert ! autovideosink sync=false t. ! queue ! mpegtsmux ! filesink location=/sdcard/TEST.ts";
    			//		+ " avdec_h264 ! queue ! autovideosink sync=false splitter. ! queue ! filesink location=/sdcard/TEST.mp4";
					
    			//pipeline = "udpsrc address="+my_ip_s+" port="+my_p_s+" caps=\"application/x-rtp, media=(string)video, clock-rate=(int)90000, encoding-name=(string)H264\" ! rtph264depay  ! filesink location="+d;
			
			} catch (IOException e) {
				Log.d("PATH","PATH ",e);
				e.printStackTrace();
			}
    			break;
    		default:
    			pipeline = "udpsrc address="+my_ip_s+" port="+my_p_s+" caps=\"application/x-rtp, media=(string)video, clock-rate=(int)90000, encoding-name=(string)H264, framerate=90\" ! rtph264depay  ! avdec_h264 ! autovideosink sync=false";
    	}
*/
		
    	Log.d("PIPELINE","PIPELINE "+pipeline);
    	nativeConfig(pipeline);
    	
    	if (rpicam!=null) rpicam.stop();
    	rpicam = new RPiCam(this,Utils.ip_b2b(rpi_ip),rpi_cp,Utils.ip_b2b(my_ip),my_p,stream_type);
    	
    	if (rpi!=null) rpi.stop();
    	rpi = new RPiController(this,Utils.ip_b2b(rpi_ip),rpi_p);
    	rpi.start();

    	lq.setRpiIp((int) Utils.ip_s2i(rpi_ip_s));
    	
    	//if (gc!=null) gc.stop();
    	//gc = new GameController();
    	//gc.start();
    }
        
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) //return from options menu
    {
                super.onActivityResult(requestCode, resultCode, data);

                    if(requestCode==1)
                    {
                    	initializePlayer();
                    	updateUI();
                    }

    }
    
	/**
	 * Touch listener to use for in-layout UI controls to delay hiding the
	 * system UI. This is to prevent the jarring behavior of controls going away
	 * while interacting with activity UI.
	 */
	View.OnTouchListener mDelayHideTouchListener = new View.OnTouchListener() {
		@Override
		public boolean onTouch(View view, MotionEvent motionEvent) {
			if (AUTO_HIDE) {
				delayedHide(AUTO_HIDE_DELAY_MILLIS);
			}
			return false;
		}
	};

	Handler mHideHandler = new Handler();
	Runnable mHideRunnable = new Runnable() {
		@Override
		public void run() {
			mSystemUiHider.hide();
		}
	};

	/**
	 * Schedules a call to hide() in [delay] milliseconds, canceling any
	 * previously scheduled calls.
	 */
	private void delayedHide(int delayMillis) {
		mHideHandler.removeCallbacks(mHideRunnable);
		mHideHandler.postDelayed(mHideRunnable, delayMillis);
	}
	
    // Called from native code. Native code calls this once it has created its pipeline and
    // the main loop is running, so it is ready to accept commands.
    private void onGStreamerInitialized () {
        Log.i ("GStreamer", "onGStreamerInitialized");
        nativePlay();
        updateUI();
    }
    
    protected void onSaveInstanceState (Bundle outState) {    	
        Log.d ("GStreamer", "onSaveInstanceState");
    }
    
    protected void onDestroy() {
    	rpicam.stop();
    	rpi.stop();
        nativeFinalize();
        Log.d("RPI","RPI onDestroy");
        super.onDestroy();
    }
    
    static {
        System.loadLibrary("gstreamer_android");
        System.loadLibrary("RPiCameraStreamer");
        nativeClassInit();
    }
    
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d("GStreamer", "Surface changed to format " + format + " width "
                + width + " height " + height);
        nativeSurfaceInit (holder.getSurface());
    }

    public void surfaceCreated(SurfaceHolder holder) {
        Log.d("GStreamer", "Surface created: " + holder.getSurface());
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d("GStreamer", "Surface destroyed");
        nativeSurfaceFinalize ();
    }
    
    private void clearMsg(int delay) {
    	final Handler handler = new Handler();
    	handler.postDelayed(new Runnable() {
    	  @Override
    	  public void run() {
    	    message = "";
    	    updateUI();
    	  }
    	}, delay);
    }
    
    private void _updateUI() {
        final TextView tv = (TextView) this.findViewById(R.id.textview_message);
        final Button mButton=(Button) this.findViewById(R.id.bottom_button);
    	if (message.length()>0) {
    		tv.setVisibility(View.VISIBLE);
    		tv.setText(message);
    		//clearMsg(3500);
    	} else {
    		tv.setVisibility(View.INVISIBLE);
    	}
      
  		if (is_running)
  			mButton.setText("Stop");
  		else mButton.setText("Start");
  		
  		final TextView tvi = (TextView) this.findViewById(R.id.textview_info);
  		tvi.setText(infobox.getText());
  		if (infobox.visible) tvi.setVisibility(View.VISIBLE);
  		else tvi.setVisibility(View.INVISIBLE);
  			
    }
    
    private void updateUI() {

        runOnUiThread (new Runnable() {
            public void run() { _updateUI(); }
          });
    }
	@Override
	public void notify(int status, String msg) {
		if (status==0) {
			message = msg;
			Log.d("NOTIFY","NOTIFY "+msg);
		}
		
		if (!split_screen && status==1) {
			infobox.visible = true;
			infobox.setLatency(rpi.getLatency());
			infobox.setPingRate(rpi.getRate());
			infobox.setAltitude(rpi.getAltitude());
			infobox.setStatus(rpi.getAVRStatus(),rpi.getAVRCode());
			lq.update();
			if (lq.isLocal()) {
				infobox.setLQLevel(lq.getLevel());
				infobox.setLQLinkSpeed(lq.getLinkSpeed());
			}
			else {
				infobox.setLQLevel(rpi.getRSSI());
				infobox.setLQLinkSpeed(rpi.getLinkSpeed());
			}

			
			long probe = System.currentTimeMillis();
			long bytes = TrafficStats.getUidTxBytes(uid);
			bytes += TrafficStats.getUidRxBytes(uid);
			currentSpeed = (float)(bytes-lastBytes)/(float)(probe-lastProbe); //kbytes per sec
			lastProbe = probe;
			lastBytes = bytes;
			
			infobox.setNetworkSpeed(currentSpeed);
			
		} else {
			infobox.visible = false;
		}
		
		updateUI();
		
	}
	
	
	private ArrayList getGameControllerIds() {
	    ArrayList gameControllerDeviceIds = new ArrayList();
	    int[] deviceIds = InputDevice.getDeviceIds();
	    for (int deviceId : deviceIds) {
	        InputDevice dev = InputDevice.getDevice(deviceId);
	        int sources = dev.getSources();

	        // Verify that the device has gamepad buttons, control sticks, or both.
	        if (((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
	                || ((sources & InputDevice.SOURCE_JOYSTICK)
	                == InputDevice.SOURCE_JOYSTICK)) {
	            // This device is a game controller. Store its device ID.
	            if (!gameControllerDeviceIds.contains(deviceId)) {
	                gameControllerDeviceIds.add(deviceId);
	            }
	        }
	    }
	    return gameControllerDeviceIds;
	}
	
	private void initGC() {
		ArrayList al = getGameControllerIds();
		if (al.size()<=0) {
			notify(0,"Game Controller not found!");
			rpi.setController(false);
			gcdevid = -1;
		}
		else {
			gcdevid = (int)al.get(0);
			rpi.setController(true);
		}
	}
	
	public boolean dispatchKeyEvent(KeyEvent event) {
		boolean handled = false;
		int key;
		int src;
		src = event.getSource() & (InputDevice.SOURCE_DPAD | InputDevice.SOURCE_GAMEPAD);
		if (event.getAction() == KeyEvent.ACTION_UP && event.getDeviceId()==gcdevid && src!=0) {		
			handled = true;
			key = event.getKeyCode();
			switch (key) {
				case 96: rpi.startAltHold(); break; //X
				case 97: rpi.exitAltHold(); break; //circle
				case 99: rpi.testFailsafe(); break; //square
				case 100: rpi.toggleMode(); break; //triangle
				case 108: rpi.reset(); break; //start
				case 109: rpi.flogsave(); break; //select
				case 103: rpi.incAltitude(); break;//R1
				case 105: rpi.decAltitude(); break;//R2
			}
			//notify(0,"BUTTON: "+key);
		}
		//return true;
		if (handled) return true;
		return super.dispatchKeyEvent(event);
	}
	
	static float map(float x, float in_min, float in_max, float out_min, float out_max) {
		  return (x - in_min) * (out_max - out_min) / (in_max - in_min) + out_min;
		}
	
	public boolean dispatchGenericMotionEvent (MotionEvent ev) {
		boolean handled = false;
		int src;
		src = ev.getSource() & (InputDevice.SOURCE_JOYSTICK);
		if (ev.getDeviceId() == gcdevid && src!=0) {
			handled = true;
			rpi.yprt[0] = (int)map(-ev.getAxisValue(MotionEvent.AXIS_X),-1,1,-y_max,y_max);
			rpi.yprt[2] = (int)map(ev.getAxisValue(MotionEvent.AXIS_Z),-1,1,-pr_max,pr_max);
			rpi.yprt[1] = (int)map(-ev.getAxisValue(MotionEvent.AXIS_RZ),-1,1,-pr_max,pr_max);
			float t = -ev.getAxisValue(MotionEvent.AXIS_Y);
			rpi.yprt[3] = (int)map(t,-1,1,t_max-(2*(t_max-t_min)),t_max);
			infobox.setThrottle((int)(t*100));
			//notify(0,"Y:"+rpi.yprt[0]+" P:"+rpi.yprt[1]+" R:"+rpi.yprt[2]+" T:"+rpi.yprt[3]);
		}
		if (handled) return true;
		
		return false;
	}
}

