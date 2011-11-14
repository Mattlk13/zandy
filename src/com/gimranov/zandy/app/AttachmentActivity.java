/*******************************************************************************
 * This file is part of Zandy.
 * 
 * Zandy is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Zandy is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with Zandy.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package com.gimranov.zandy.app;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;

import org.apache.http.util.ByteArrayBuffer;
import org.json.JSONException;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ListActivity;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.TextView.BufferType;
import android.widget.Toast;

import com.gimranov.zandy.app.data.Attachment;
import com.gimranov.zandy.app.data.Database;
import com.gimranov.zandy.app.data.Item;
import com.gimranov.zandy.app.task.APIRequest;
import com.gimranov.zandy.app.task.ZoteroAPITask;

/**
 * This Activity handles displaying and editing attachments. It works almost the same as
 * ItemDataActivity and TagActivity, using a simple ArrayAdapter on Bundles with the creator info.
 * 
 * This currently operates by showing the attachments for a given item
 * 
 * @author ajlyon
 *
 */
public class AttachmentActivity extends ListActivity {

	private static final String TAG = "com.gimranov.zandy.app.AttachmentActivity";
	
	static final int DIALOG_CONFIRM_NAVIGATE = 4;	
	static final int DIALOG_FILE_PROGRESS = 6;	
	static final int DIALOG_CONFIRM_DELETE = 5;	
	static final int DIALOG_NOTE = 3;
	static final int DIALOG_NEW = 1;
	
	public Item item;
	private ProgressDialog mProgressDialog;
	private ProgressThread progressThread;
	private Database db;
	
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        db = new Database(this);
        
        /* Get the incoming data from the calling activity */
        final String itemKey = getIntent().getStringExtra("com.gimranov.zandy.app.itemKey");
        Item item = Item.load(itemKey, db);
        this.item = item;
        
        this.setTitle(getResources().getString(R.string.attachments_for_item,item.getTitle()));
        
        ArrayList<Attachment> rows = Attachment.forItem(item, db);
        
        /* 
         * We use the standard ArrayAdapter, passing in our data as a Attachment.
         * Since it's no longer a simple TextView, we need to override getView, but
         * we can do that anonymously.
         */
        setListAdapter(new ArrayAdapter<Attachment>(this, R.layout.list_attach, rows) {
        	@Override
        	public View getView(int position, View convertView, ViewGroup parent) {
        		View row;
        		
                // We are reusing views, but we need to initialize it if null
        		if (null == convertView) {
                    LayoutInflater inflater = getLayoutInflater();
        			row = inflater.inflate(R.layout.list_attach, null);
        		} else {
        			row = convertView;
        		}

        		ImageView tvType = (ImageView)row.findViewById(R.id.attachment_type);
        		TextView tvSummary = (TextView)row.findViewById(R.id.attachment_summary);
        		
        		Attachment att = getItem(position);
        		Log.d(TAG, "Have an attachment: "+att.title + " fn:"+att.filename + " status:" + att.status);
        		
        		tvType.setImageResource(Item.resourceForType(att.getType()));
        		
        		try {
					Log.d(TAG, att.content.toString(4));
				} catch (JSONException e) {
					Log.e(TAG, "JSON parse exception when reading attachment content", e);
				}
        		
        		if (att.getType().equals("note")) {
        			String note = att.content.optString("note","");
        			if (note.length() > 40) {
        				note = note.substring(0,40);
        			}
        			tvSummary.setText(note);
        		} else {
        			StringBuffer status = new StringBuffer(getResources().getString(R.string.status));
        			if (att.status == Attachment.ZFS_AVAILABLE)
        				status.append(getResources().getString(R.string.attachment_zfs_available));
        			else if (att.status == Attachment.ZFS_LOCAL)
        				status.append(getResources().getString(R.string.attachment_zfs_local));
        			else
        				status.append(getResources().getString(R.string.attachment_unknown));
        			tvSummary.setText(att.title + " " + status.toString());
        		}
        		return row;
        	}
        });
        
        ListView lv = getListView();
        lv.setTextFilterEnabled(true);
        lv.setOnItemClickListener(new OnItemClickListener() {
        	// Warning here because Eclipse can't tell whether my ArrayAdapter is
        	// being used with the correct parametrization.
        	@SuppressWarnings("unchecked")
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        		// If we have a click on an entry, do something...
        		ArrayAdapter<Attachment> adapter = (ArrayAdapter<Attachment>) parent.getAdapter();
        		Attachment row = adapter.getItem(position);
        		String url = (row.url != null && !row.url.equals("")) ?
        				row.url : row.content.optString("url");
				if (!row.getType().equals("note")) {
					Bundle b = new Bundle();
        			b.putString("title", row.title);
        			b.putString("attachmentKey", row.key);
        			b.putString("content", url);
        			// 0 means download from ZFS. 1 is everything else (?)
        			String linkMode = row.content.optString("linkMode","0");
        			if (linkMode.equals("0"))
        				loadFileAttachment(b);
        			else
        				showDialog(DIALOG_CONFIRM_NAVIGATE, b);
				}
								
				if (row.getType().equals("note")) {
					Bundle b = new Bundle();
					b.putString("attachmentKey", row.key);
					b.putString("itemKey", itemKey);
					b.putString("content", row.content.optString("note", ""));
					removeDialog(DIALOG_NOTE);
					showDialog(DIALOG_NOTE, b);
				}
        	}
        });
    }
    
    @Override
    public void onDestroy() {
    	if (db != null) db.close();
    	super.onDestroy();
    }
    
    @Override
    public void onResume() {
    	if (db == null) db = new Database(this);
    	super.onResume();
    }
    
	protected Dialog onCreateDialog(int id, Bundle b) {
		final String attachmentKey = b.getString("attachmentKey");
		final String itemKey = b.getString("itemKey");
		final String content = b.getString("content");
		final String mode = b.getString("mode");
		AlertDialog dialog;
		switch (id) {			
		case DIALOG_CONFIRM_NAVIGATE:
			dialog = new AlertDialog.Builder(this)
		    	    .setTitle(getResources().getString(R.string.view_online_warning))
		    	    .setPositiveButton(getResources().getString(R.string.view), new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int whichButton) {
		        			// The behavior for invalid URIs might be nasty, but
		        			// we'll cross that bridge if we come to it.
							try {
								Uri uri = Uri.parse(content);
								startActivity(new Intent(Intent.ACTION_VIEW)
			        							.setData(uri));
							} catch (ActivityNotFoundException e) {
								// There can be exceptions here; not sure what would prompt us to have
								// URIs that the browser can't load, but it apparently happens.
								Toast.makeText(getApplicationContext(),
										getResources().getString(R.string.attachment_intent_failed_for_uri, content), 
				        				Toast.LENGTH_SHORT).show();
							}
		    	        }
		    	    }).setNeutralButton(getResources().getString(R.string.cancel), new DialogInterface.OnClickListener() {
		    	        public void onClick(DialogInterface dialog, int whichButton) {
		    	        	// do nothing
		    	        }
		    	    }).create();
			return dialog;
		case DIALOG_CONFIRM_DELETE:
			dialog = new AlertDialog.Builder(this)
		    	    .setTitle(getResources().getString(R.string.attachment_delete_confirm))
		    	    .setPositiveButton(getResources().getString(R.string.menu_delete), new DialogInterface.OnClickListener() {
						@SuppressWarnings("unchecked")
						public void onClick(DialogInterface dialog, int whichButton) {
							Attachment a = Attachment.load(attachmentKey, db);
							a.delete(db);
		    	            ArrayAdapter<Attachment> la = (ArrayAdapter<Attachment>) getListAdapter();
		    	            la.clear();
		    	            for (Attachment at : Attachment.forItem(Item.load(itemKey, db), db)) {
		    	            	la.add(at);
		    	            }
		    	        }
		    	    }).setNegativeButton(getResources().getString(R.string.cancel), new DialogInterface.OnClickListener() {
		    	        public void onClick(DialogInterface dialog, int whichButton) {
		    	        	// do nothing
		    	        }
		    	    }).create();
			return dialog;
		case DIALOG_NOTE:
			final EditText input = new EditText(this);
			input.setText(content, BufferType.EDITABLE);
			
			AlertDialog.Builder builder = new AlertDialog.Builder(this)
	    	    .setTitle(getResources().getString(R.string.note))
	    	    .setView(input)
	    	    .setPositiveButton(getResources().getString(R.string.ok), new DialogInterface.OnClickListener() {
					@SuppressWarnings("unchecked")
					public void onClick(DialogInterface dialog, int whichButton) {
	    	            Editable value = input.getText();
						if (mode != null && mode.equals("new")) {
							Log.d(TAG, "Attachment created with parent key: "+itemKey);
							Attachment att = new Attachment(getBaseContext(), "note", itemKey);
		    	            att.setNoteText(value.toString());
		    	            att.save(db);
						} else {
							Attachment att = Attachment.load(attachmentKey, db);
		    	            att.setNoteText(value.toString());
		    	            att.dirty = APIRequest.API_DIRTY;
		    	            att.save(db);
						}
	    	            ArrayAdapter<Attachment> la = (ArrayAdapter<Attachment>) getListAdapter();
	    	            la.clear();
	    	            for (Attachment a : Attachment.forItem(Item.load(itemKey, db), db)) {
	    	            	la.add(a);
	    	            }
	    	            la.notifyDataSetChanged();
	    	        }
	    	    }).setNeutralButton(getResources().getString(R.string.cancel),
	    	    		new DialogInterface.OnClickListener() {
	    	        public void onClick(DialogInterface dialog, int whichButton) {
	    	        	// do nothing
	    	        }
	    	    });
			// We only want the delete option when this isn't a new note
			if (mode == null || !"new".equals(mode)) {
				builder = builder.setNegativeButton(getResources().getString(R.string.menu_delete), new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int whichButton) {
	    	            Bundle b = new Bundle();
	    	            b.putString("attachmentKey", attachmentKey);
	    	            b.putString("itemKey", itemKey);
	    	        	removeDialog(DIALOG_CONFIRM_DELETE);
	    	        	showDialog(DIALOG_CONFIRM_DELETE, b);
	    	        }
	    	    });
			}
			dialog = builder.create();
			return dialog;
		case DIALOG_FILE_PROGRESS:
			mProgressDialog = new ProgressDialog(this);
			mProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
			mProgressDialog.setMessage(getResources().getString(R.string.attachment_downloading, b.getString("title")));
			mProgressDialog.setIndeterminate(false);
			mProgressDialog.setMax(100);
			return mProgressDialog;
		default:
			Log.e(TAG, "Invalid dialog requested");
			return null;
		}
	}
	
	protected void onPrepareDialog(int id, Dialog dialog, Bundle b) {
		switch(id) {
		case DIALOG_FILE_PROGRESS:
			mProgressDialog.setProgress(0);
			progressThread = new ProgressThread(handler, b);
			progressThread.start();
		}
	}
	
	/**
	 * This mainly is to move the logic out of the onClick callback above
	 * Decides whether to download or view, and launches the appropriate action
	 * @param b
	 */
	private void loadFileAttachment(Bundle b) {
		Attachment att = Attachment.load(b.getString("attachmentKey"), db);
		if (!ServerCredentials.sBaseStorageDir.exists())
			ServerCredentials.sBaseStorageDir.mkdir();
		if (!ServerCredentials.sDocumentStorageDir.exists())
			ServerCredentials.sDocumentStorageDir.mkdir();
		
		File attFile = new File(att.filename);
		
		if (att.status == Attachment.ZFS_AVAILABLE
				// Zero-length or nonexistent gives length == 0
				|| (attFile != null && attFile.length() == 0)) {				
			Log.d(TAG,"Starting to try and download ZFS-available attachment (status: "+att.status+", fn: "+att.filename+")");
			showDialog(DIALOG_FILE_PROGRESS, b);
		} else if (att.status == Attachment.ZFS_LOCAL) {
			Log.d(TAG,"Starting to display local attachment");
			Uri uri = Uri.fromFile(new File(att.filename));
			String mimeType = att.content.optString("mimeType",null);
			try {
				startActivity(new Intent(Intent.ACTION_VIEW)
							.setDataAndType(uri,mimeType));
			} catch (ActivityNotFoundException e) {
				Log.e(TAG, "No activity for intent", e);
				Toast.makeText(getApplicationContext(),
						getResources().getString(R.string.attachment_intent_failed, mimeType), 
        				Toast.LENGTH_SHORT).show();
			}
		}
	}
	
	/**
	 * Refreshes the current list adapter
	 */
	@SuppressWarnings("unchecked")
	private void refreshView() {
		ArrayAdapter<Attachment> la = (ArrayAdapter<Attachment>) getListAdapter();
        la.clear();
        for (Attachment at : Attachment.forItem(item, db)) {
        	la.add(at);
        }
	}
	
	final Handler handler = new Handler() {
		public void handleMessage(Message msg) {
			if (ProgressThread.STATE_DONE == msg.arg2) {
				if(mProgressDialog.isShowing())
					dismissDialog(DIALOG_FILE_PROGRESS);
				refreshView();
				return;
			}
			
			int total = msg.arg1;
			mProgressDialog.setProgress(total);
			if (total >= 100) {
				dismissDialog(DIALOG_FILE_PROGRESS);
				progressThread.setState(ProgressThread.STATE_DONE);
			}
		}
	};
	
	private class ProgressThread extends Thread {
		Handler mHandler;
		Bundle arguments;
		final static int STATE_DONE = 5;
		final static int STATE_RUNNING = 1;
		int mState;
		
		ProgressThread(Handler h, Bundle b) {
			mHandler = h;
			arguments = b;
		}
		
		public void run() {
			mState = STATE_RUNNING;
			
			// Setup
			final String attachmentKey = arguments.getString("attachmentKey");

			Attachment att = Attachment.load(attachmentKey, db);
			SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
			String sanitized = att.title.replace(' ', '_')
					.replaceFirst("^(.*?)(\\.?[^.]*)$", "$1"+"_"+att.key+"$2");
			File file = new File(ServerCredentials.sDocumentStorageDir,sanitized);
			if (!ServerCredentials.sBaseStorageDir.exists())
				ServerCredentials.sBaseStorageDir.mkdir();
			if (!ServerCredentials.sDocumentStorageDir.exists())
				ServerCredentials.sDocumentStorageDir.mkdir();
			
			URL url;
			try {
				url = new URL(att.url+"?key="+settings.getString("user_key",""));
				//this is the downloader method
                long startTime = System.currentTimeMillis();
                Log.d(TAG, "download beginning");
                Log.d(TAG, "download url:" + url.toString());
                Log.d(TAG, "downloaded file name:" + file.getPath());
                /* Open a connection to that URL. */
                URLConnection ucon = url.openConnection();

                /*
                 * Define InputStreams to read from the URLConnection.
                 */
                InputStream is = ucon.getInputStream();
                BufferedInputStream bis = new BufferedInputStream(is, 16000);

                ByteArrayBuffer baf = new ByteArrayBuffer(50);
                int current = 0;
                
                /*
                 * Read bytes to the Buffer until there is nothing more to read(-1).
                 */
    			while (mState == STATE_RUNNING 
    					&& (current = bis.read()) != -1) {
                        baf.append((byte) current);
                        
                        if (baf.length() % 2048 == 0) {
                        	Message msg = mHandler.obtainMessage();
                        	// XXX do real length later
                        	Log.d(TAG, baf.length() + " downloaded so far");
                        	msg.arg1 = baf.length() % 100;
                        	mHandler.sendMessage(msg);
                        }
                }

                /* Convert the Bytes read to a String. */
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(baf.toByteArray());
                fos.close();
                Log.d(TAG, "download ready in "
                                + ((System.currentTimeMillis() - startTime) / 1000)
                                + " sec");
	        } catch (IOException e) {
	                Log.e(TAG, "Error: ",e);
	        }
			att.filename = file.getPath();
			File newFile = new File(att.filename);
			if (newFile.length() > 0) {
				att.status = Attachment.ZFS_LOCAL;
				Log.d(TAG,"File downloaded: "+att.filename);
			} else {
				Log.d(TAG, "File not downloaded: "+att.filename);
				att.status = Attachment.ZFS_AVAILABLE;				
			}
			att.save(db);
        	Message msg = mHandler.obtainMessage();
        	msg.arg2 = STATE_DONE;
        	mHandler.sendMessage(msg);
		}
		
		public void setState(int state) {
			mState = state;
		}
	}
               
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.zotero_menu, menu);
        return true;
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
		Bundle b = new Bundle();
        // Handle item selection
        switch (item.getItemId()) {
        case R.id.do_sync:
        	if (!ServerCredentials.check(getApplicationContext())) {
            	Toast.makeText(getApplicationContext(), getResources().getString(R.string.sync_log_in_first), 
        				Toast.LENGTH_SHORT).show();
            	return true;
        	}
        	Log.d(TAG, "Preparing sync requests, starting with present item");
        	new ZoteroAPITask(getBaseContext()).execute(APIRequest.update(this.item));
        	Toast.makeText(getApplicationContext(), getResources().getString(R.string.sync_started), 
    				Toast.LENGTH_SHORT).show();
        	
        	return true;
        case R.id.do_new:
			b.putString("itemKey", this.item.getKey());
			b.putString("mode", "new");
        	removeDialog(DIALOG_NOTE);
        	showDialog(DIALOG_NOTE, b);
            return true;
        case R.id.do_prefs:
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }
}
