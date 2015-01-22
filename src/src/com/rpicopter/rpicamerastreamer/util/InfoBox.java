package com.rpicopter.rpicamerastreamer.util;

public class InfoBox {
	public boolean visible;
	private String text;
	
	private int prate;
	private int platency;
	private int pthrottle;
	private int plqs;
	private int plql;
	private int paltitude; //in cm
	private float pns;
	
	public InfoBox() {
		text = "";
		visible = true;
		prate = 0;
		platency = 0;
		plql = 0;
		plqs = 0;
	}

	public void show() {
		
	}
	
	public void hide() {
		
	}
	
	public String getText() {
		float alt = paltitude/(float)100;
		text = "Altitude: "+String.format("%.1f", alt)+"m\n";
		text += "Throttle: "+pthrottle+"%\n";
		text += "Last ping: "+platency+"ms\n";
		text += "Ping rate: "+prate+"%\n";
		text += "L.Speed: "+plqs+"Mbps\n";
		text += "TX/RX: "+String.format("%.1f", pns)+"KBps\n";
		text += "L.RSSI: "+plql+"dBm";
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
	
	public void setLQLevel(int t) {
		plql = t;
	}
	public void setLQLinkSpeed(int t) {
		plqs = t;
	}
	
	public void setNetworkSpeed(float t) {
		pns = t;
	}
	
	
}
