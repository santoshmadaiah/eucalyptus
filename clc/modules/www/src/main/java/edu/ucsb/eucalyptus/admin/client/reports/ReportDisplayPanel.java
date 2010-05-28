package edu.ucsb.eucalyptus.admin.client.reports;

import com.google.gwt.user.client.ui.Frame;
import com.google.gwt.user.client.ui.HorizontalPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.VerticalPanel;
import com.google.gwt.user.client.ui.Widget;
import edu.ucsb.eucalyptus.admin.client.AccountingControl;
import edu.ucsb.eucalyptus.admin.client.util.Observer;

public class ReportDisplayPanel extends VerticalPanel implements Observer {
  private final AccountingControl controller;
  private HorizontalPanel         actionBar;
  private final Frame             report;
  private final HorizontalPanel rightBar;
  private final HorizontalPanel leftBar;
  private final HorizontalPanel topBar;
  
  ReportDisplayPanel( AccountingControl controller ) {
    this.ensureDebugId( "ReportDisplayPanel" );
    this.setStyleName( AccountingControl.RESOURCES.DISPLAY_PANEL_STYLE );
    this.controller = controller;
    this.topBar = new HorizontalPanel();
    this.topBar.setStyleName( AccountingControl.RESOURCES.DISPLAY_BAR_STYLE );
    this.leftBar = new HorizontalPanel();
    this.leftBar.setHorizontalAlignment( ALIGN_LEFT );
    this.rightBar = new HorizontalPanel();
    this.rightBar.setHorizontalAlignment( ALIGN_RIGHT );
    this.report = new Frame( );
    this.report.setStyleName( AccountingControl.RESOURCES.REPORT_FRAME_STYLE );
  }
  
  public void update( ) {
    if ( this.controller.isReady( ) ) {
      this.report.setUrl( this.controller.getCurrentUrl( ReportType.HTML ) );
      if( this.actionBar != null ) {
        for( Widget w : this.actionBar ) {
          if( w instanceof Observer ) {
            ( ( Observer ) w ).update();
          }
        }
      }
    }
  }
  
  public void redraw( ) {
    this.clear( );
    this.topBar.clear( );
    this.rightBar.clear( );
    this.leftBar.clear( );
    this.topBar.add( this.leftBar );
    for ( final ReportAction a : ReportAction.values( ) ) {
      this.leftBar.add( a.makeImageButton( ReportDisplayPanel.this.controller ) );
    }
    this.topBar.add( this.rightBar );
    for ( final ReportType r : ReportType.values( ) ) {
      this.rightBar.add( r.makeImageButton( ReportDisplayPanel.this.controller ) );
    }
    this.add( this.topBar );
    this.add( this.report );
    this.update( );
  }
  
}
