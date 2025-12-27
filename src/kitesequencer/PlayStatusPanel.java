package kitesequencer;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.JPanel;

class PlayStatusPanel extends JPanel {
	private PlayStatus playStatus = PlayStatus.STOP;
	
	public void setPlayStatus(PlayStatus status) {
		this.playStatus = status;
	}
	
	public PlayStatus getPlayStatus() {
		return playStatus;
	}
	
	@Override
	public void paint(Graphics g_) {
		Graphics2D g = (Graphics2D) g_;
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setPaint(Color.WHITE);
		g.setFont(new Font("Monospaced",Font.BOLD,getHeight()-1));
		g.fillRect(0,0,getWidth(),getHeight());
		g.setPaint(this.playStatus.color);
		g.drawString(playStatus.toString(), 2, getHeight()-2);
		//g.fillRoundRect(1,1,getWidth()-2,getHeight()-2,10,10);		
	}
}