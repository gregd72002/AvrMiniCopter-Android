package com.rpicopter.rpicamerastreamer.util;

public class InfoBox {
	public boolean visible;
	private String text;
	
	private int prate;
	private int platency;
	private int pthrottle;
	private int paltitude; //in cm
	
	public InfoBox() {
		text = "";
		visible = true;
		prate = 0;
		platency = 0;
	}

	public String getText() {
		float alt = paltitude/(float)100;
		text = "Altitude: "+String.format("%.1f", alt)+"m\n\n";
		text += "Throttle: "+pthrottle+"%\n\n";
		text += "Last ping: "+platency+"ms\n\n";
		text += "Ping rate:"+prate+"%\n";
		return text;
	}
	
	public void setPingRate(float rate) {
		prate = (int)(rate*100);
	}
	
	public void setLatency(int l) {
		platency = l;
	}
	
	public void setAltitude(int alt) {
		paltitude = alt;
	}

	public void setThrottle(int t) {
		pthrottle = t;
	}
}
