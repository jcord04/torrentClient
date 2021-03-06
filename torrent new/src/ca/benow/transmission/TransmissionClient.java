package ca.benow.transmission;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import ca.benow.transmission.model.AddedTorrentInfo;
import ca.benow.transmission.model.SessionStatus;
import ca.benow.transmission.model.TorrentStatus;
import ca.benow.transmission.model.TorrentStatus.TorrentField;
import ca.benow.transmission.model.TrackerPair;
import ca.benow.transmission.model.TransmissionSession;
import ca.benow.transmission.model.TransmissionSession.SessionField;
import ca.benow.transmission.model.TransmissionSession.SessionPair;

/**
 * The main class for interacting with transmission. Create an instance with
 * appropriate parameters and call the methods. If possible, re-use the instance
 * to avoid slight network overhead.
 * <p/>
 * Here's an example.
 * 
 * <pre>
 * // initialize log4j
 * DOMConfigurator.configure(&quot;etc/logging.xml&quot;);
 * 
 * TransmissionClient client = new TransmissionClient(new URL(
 * 		&quot;http://transmission:transmission@localhost:9091/transmission/rpc&quot;));
 * List&lt;TorrentStatus&gt; torrents = client.getAllTorrents();
 * for (TorrentStatus curr : torrents)
 * 	System.out.println(curr);
 * </pre>
 * 
 * By tuning the log4j config, it is possible to see the JSON RPC request and
 * response text, which might be useful for debugging.
 * 
 * @author andy
 * 
 */
public class TransmissionClient {

	private static final String ID_RECENTLY_ACTIVE = "recently-active";
	private static final String SESSION_HEADER = "X-Transmission-Session-Id";
	private static final int DEFAULT_PORT = 9091;
	private static final Logger log = Logger.getLogger(TransmissionClient.class);

	public static final int PRIORITY_LOW = -1;
	public static final int PRIORITY_NORMAL = 0;
	public static final int PRIORITY_HIGH = 1;

	private final URL url;
	private String user;
	private String pass;
	private String userCrypt;
	private String sessionId;
	private static int tag = 0;

	/**
	 * Creates a new client that connects to a given url. URL should be something
	 * similar to
	 * 
	 * <pre>
	 * http://transmission:transmission@localhost:9091/transmission/rpc
	 * </pre>
	 * 
	 * @param url
	 */
	public TransmissionClient(URL url) {
		this.url = url;
		if (url.getUserInfo() != null) {
			String uinfo = url.getUserInfo();
			if (uinfo != null)
				userCrypt = Base64.encode(uinfo);
		}
	}

	/**
	 * Creates a new client that connects to a given host on a given port with the
	 * given user and password.
	 * 
	 * @param host
	 *          host to connect to
	 * @param port
	 *          port to connect on
	 * @param user
	 *          user to connect as
	 * @param pass
	 *          password for user
	 */
	public TransmissionClient(String host, int port, String user, String pass) {
		this(createURL(host, port, user, pass));
	}

	/**
	 * Creates a new client that connects to a given host on the default port
	 * (9091) with the given user and pass
	 * 
	 * @param host
	 *          host to connect to
	 * @param user
	 *          user to connect as
	 * @param pass
	 *          password for user
	 */
	public TransmissionClient(String host, String user, String pass) {
		this(createURL(host, DEFAULT_PORT, user, pass));
	}

	/**
	 * Create a new client that connects to a given host on the default port
	 * (9091) with the default user/pass (transmission/transmission)
	 * 
	 * @param host
	 *          host to connect to
	 */
	public TransmissionClient(String host) {
		this(host, DEFAULT_PORT);
	}

	/**
	 * Create a new client that connects to given host and port with default
	 * user/pass (transmission/transmission)
	 * 
	 * @param host
	 *          host to connect to
	 * @param port
	 *          port to connect on
	 */
	public TransmissionClient(String host, int port) {
		this(host, port, "transmission", "transmission");
	}

	/**
	 * Creates a new client that connects to the local transmission using default
	 * parameters
	 */
	public TransmissionClient() {
		this(createURL("localhost", 9091, "transmission", "transmission"));
	}

	private static URL createURL(String host, int port, String user, String pass) {
		try {
			return new URL("http://" + (user == null ? "" : user + ":" + pass + "@")
					+ host + ":" + port + "/transmission/rpc");
		} catch (MalformedURLException e) {
			throw new RuntimeException("The impossible happened, again.", e);
		}
	}

	/**
	 * Send a command to Transmission.
	 * 
	 * @param name
	 *          name of command, which forms the 'method' in request
	 * @param args
	 *          arguments which for body of 'arguments' in request
	 * @return json object containing result of 'arguments' in response, if any
	 * @throws IOException
	 *           on problem communicating
	 * @throws TransmissionException
	 *           on Transmission problem when performing the command
	 */
	public JSONObject sendCommand(String name, JSONObject args)
			throws IOException, TransmissionException {
		HttpURLConnection hconn = (HttpURLConnection) url.openConnection();
		hconn.setRequestMethod("POST");
		hconn.setDoOutput(true);
		if (userCrypt != null)
			hconn.setRequestProperty("Authorization", "Basic " + userCrypt);
		if (sessionId != null)
			hconn.setRequestProperty(SESSION_HEADER, sessionId);

		JSONObject command = new JSONObject();
		command.put("method", name);
		command.put("arguments", args);
		command.put("tag", "" + tag++);

		String json = command.toString(2);
		OutputStream out = hconn.getOutputStream();
		out.write((json + "\r\n\r\n").getBytes());
		out.flush();
		out.close();

		BufferedReader in;
		try {
			in = new BufferedReader(new InputStreamReader(hconn.getInputStream()));
			if (log.isDebugEnabled())
				log.debug("Wrote:\n" + json);
		} catch (IOException e) {
			if (hconn.getResponseCode() == 409) {
				String sessId = hconn.getHeaderField(SESSION_HEADER);
				if (sessId != null) {
					log.debug("Reconnecting with new session id");
					this.sessionId = sessId;
					return sendCommand(name, args);
				}
			}
			throw e;
		}
		String msg = "";
		String line = in.readLine();
		while (line != null) {
			msg += line + "\n";
			line = in.readLine();
		}
		JSONObject result;
		try {
			JSONTokener toker = new JSONTokener(msg);
			result = new JSONObject(toker);
		} catch (JSONException e) {
			log.error("Error parsing json: " + msg);
			throw e;
		}

		if (log.isDebugEnabled())
			log.debug("Read:\n" + result.toString(2));

		String resultStr = result.getString("result");
		if (!resultStr.equals("success"))
			throw new TransmissionException(resultStr, command.toString(2), msg);

		JSONObject resultArgs = null;
		if (result.has("arguments"))
			resultArgs = result.getJSONObject("arguments");
		return resultArgs;
	}

	/**
	 * Get status of torrents
	 * 
	 * @param ids
	 *          optional ids of torrents to fetch status for, if not given, status
	 *          for all torrents will be fetched
	 * @param requestedFields
	 *          information fields to fetch, if not given, only the id and name
	 *          fields are fetched
	 * @return status for requested torrents
	 * @throws IOException
	 */
	public List<TorrentStatus> getTorrents(int[] ids,
			TorrentStatus.TorrentField[] requestedFields) throws IOException {
		JSONObject args = new JSONObject();
		if (ids != null && ids.length > 0) {
			JSONArray idAry = new JSONArray();
			for (int i = 0; i < ids.length; i++)
				idAry.put(ids[i]);
			args.put("ids", idAry);
		}
		if (requestedFields == null)
			requestedFields = TorrentStatus.defaultFields;
		else {
			if (requestedFields.length > 0) {
				boolean hasAll = false;
				for (int i = 0; i < requestedFields.length; i++) {
					if (requestedFields[i].equals(TorrentField.all))
						hasAll = true;
				}
				if (hasAll) {
					requestedFields = new TorrentField[TorrentField.values().length - 1];
					for (int i = 0; i < TorrentField.values().length; i++)
						requestedFields[i] = TorrentField.values()[i + 1];
				}
			}
		}
		JSONArray fields = new JSONArray();
		for (int i = 0; i < requestedFields.length; i++)
			fields
					.put(TorrentStatus.fieldNameByFieldPos[requestedFields[i].ordinal()]);
		args.put("fields", fields);

		List<TorrentStatus> torrents = new ArrayList<TorrentStatus>();
		JSONObject result = sendCommand("torrent-get", args);
		JSONArray torAry = result.getJSONArray("torrents");
		for (int i = 0; i < torAry.length(); i++)
			torrents.add(new TorrentStatus(torAry.getJSONObject(i)));

		return torrents;
	}

	public List<TorrentStatus> getAllTorrents(TorrentField[] torrentFields)
			throws IOException {
		return getTorrents(null, torrentFields);
	}

	public List<TorrentStatus> getAllTorrents() throws IOException {
		return getTorrents(null, null);
	}

	/**
	 * Adds a new torrent by name or url
	 * 
	 * @param downloadDir
	 *          path to download the torrent to
	 * @param torrentFileNameOrURL
	 *          local filename or url to torrent
	 * @param paused
	 *          if true, don't start the torrent
	 * @param peerLimit
	 *          maximum number of peers
	 * @param bandwidthPriority
	 *          one of the PRIORITY_* constants
	 * @param filesWanted
	 *          indices of file(s) to download
	 * @param filesUnwanted
	 *          indices of file(s) to not download
	 * @param priorityHigh
	 *          indices of high-priority file(s)
	 * @param priorityLow
	 *          indices of low-priority file(s)
	 * @param priorityNormal
	 *          indices of normal-priority file(s)
	 * @return info about added torrent
	 * @throws IOException
	 */
	public AddedTorrentInfo addTorrent(String downloadDir,
			String torrentFileNameOrURL, boolean paused, int peerLimit,
			int bandwidthPriority, int[] filesWanted, int[] filesUnwanted,
			int[] priorityHigh, int[] priorityLow, int[] priorityNormal)
			throws IOException {
		return addTorrent(downloadDir, torrentFileNameOrURL, null, paused,
				peerLimit, bandwidthPriority, filesWanted, filesUnwanted, priorityHigh,
				priorityLow, priorityNormal);
	}

	/**
	 * Adds a new torrent directly from torrent data
	 * 
	 * @param downloadDir
	 *          path to download the torrent to
	 * @param metaInfo
	 *          inputstream to torrent contents (will be encoded)
	 * @param paused
	 *          if true, don't start the torrent
	 * @param peerLimit
	 *          maximum number of peers
	 * @param bandwidthPriority
	 *          one of the PRIORITY_* constants
	 * @param filesWanted
	 *          indices of file(s) to download
	 * @param filesUnwanted
	 *          indices of file(s) to not download
	 * @param priorityHigh
	 *          indices of high-priority file(s)
	 * @param priorityLow
	 *          indices of low-priority file(s)
	 * @param priorityNormal
	 *          indices of normal-priority file(s)
	 * @return info about added torrent
	 * @throws IOException
	 */
	public AddedTorrentInfo addTorrent(String downloadDir, InputStream metaInfo,
			boolean paused, int peerLimit, int bandwidthPriority, int[] filesWanted,
			int[] filesUnwanted, int[] priorityHigh, int[] priorityLow,
			int[] priorityNormal) throws IOException {
		return addTorrent(downloadDir, null, metaInfo, paused, peerLimit,
				bandwidthPriority, filesWanted, filesUnwanted, priorityHigh,
				priorityLow, priorityNormal);
	}

	private AddedTorrentInfo addTorrent(String downloadDir,
			String torrentFileNameOrURL, InputStream metaInfo, boolean paused,
			int peerLimit, int bandwidthPriority, int[] filesWanted,
			int[] filesUnwanted, int[] priorityHigh, int[] priorityLow,
			int[] priorityNormal) throws IOException {
		JSONObject obj = new JSONObject();
		if (downloadDir != null)
			obj.put("download-dir", downloadDir);
		if (torrentFileNameOrURL == null && metaInfo == null)
			throw new NullPointerException(
					"A torrentFileNameOrURL or metaInfo parameter is required");
		if (torrentFileNameOrURL != null)
			obj.put("filename", torrentFileNameOrURL);
		if (metaInfo != null) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			Base64.encode(metaInfo, out);
			String encoded = new String(out.toByteArray());
			obj.put("metainfo", encoded);
		}
		obj.put("paused", paused);
		if (peerLimit >= 0)
			obj.put("peer-limit", peerLimit);
		obj.put("peer-limit", bandwidthPriority);
		if (filesWanted != null && filesWanted.length > 0)
			obj.put("files-wanted", new JSONArray(filesWanted));
		if (filesUnwanted != null && filesUnwanted.length > 0)
			obj.put("files-unwanted", new JSONArray(filesUnwanted));
		if (priorityHigh != null && priorityHigh.length > 0)
			obj.put("priority-high", new JSONArray(priorityHigh));
		if (priorityLow != null && priorityLow.length > 0)
			obj.put("priority-low", new JSONArray(priorityLow));
		if (priorityNormal != null && priorityNormal.length > 0)
			obj.put("priority-normal", new JSONArray(priorityNormal));

		JSONObject result = sendCommand("torrent-add", obj);
		return new AddedTorrentInfo(result.getJSONObject("torrent-added"));
	}

	/**
	 * Start given torrents
	 * 
	 * @param ids
	 *          numerical ids, string hashes or the ID_RECENTLY_ADDED constant
	 * @throws IOException
	 */
	public void startTorrents(Object... ids) throws IOException {
		if (ids == null)
			throw new NullPointerException("At least one id is required");
		JSONObject obj = new JSONObject();
		if (ids.length == 1)
			obj.put("ids", ids[1]);
		else {
			JSONArray ary = new JSONArray();
			for (int i = 0; i < ids.length; i++)
				ary.put(ids[i]);
			obj.put("ids", ary);
		}
		sendCommand("torrent-start", obj);
	}

	/**
	 * Stop given torrents
	 * 
	 * @param ids
	 *          numerical ids, string hashes or the ID_RECENTLY_ADDED constant
	 * @throws IOException
	 */
	public void stopTorrents(Object... ids) throws IOException {
		if (ids == null)
			throw new NullPointerException("At least one id is required");
		JSONObject obj = new JSONObject();
		if (ids.length == 1)
			obj.put("ids", ids[1]);
		else {
			JSONArray ary = new JSONArray();
			for (int i = 0; i < ids.length; i++)
				ary.put(ids[i]);
			obj.put("ids", ary);
		}
		sendCommand("torrent-stop", obj);
	}

	/**
	 * Verify given torrents
	 * 
	 * @param ids
	 *          numerical ids, string hashes or the ID_RECENTLY_ADDED constant
	 * @throws IOException
	 */
	public void verifyTorrents(Object... ids) throws IOException {
		if (ids == null)
			throw new NullPointerException("At least one id is required");
		JSONObject obj = new JSONObject();
		if (ids.length == 1)
			obj.put("ids", ids[1]);
		else {
			JSONArray ary = new JSONArray();
			for (int i = 0; i < ids.length; i++)
				ary.put(ids[i]);
			obj.put("ids", ary);
		}
		sendCommand("torrent-verify", obj);
	}

	/**
	 * Reannounce (fetch new peers) for given torrents
	 * 
	 * @param ids
	 *          numerical ids, string hashes or the ID_RECENTLY_ADDED constant
	 * @throws IOException
	 */
	public void reannounceTorrents(Object... ids) throws IOException {
		if (ids == null)
			throw new NullPointerException("At least one id is required");
		JSONObject obj = new JSONObject();
		if (ids.length == 1)
			obj.put("ids", ids[1]);
		else {
			JSONArray ary = new JSONArray();
			for (int i = 0; i < ids.length; i++)
				ary.put(ids[i]);
			obj.put("ids", ary);
		}
		sendCommand("torrent-reannounce", obj);
	}

	/**
	 * Sets properties of selected torrents
	 * 
	 * @param ids
	 *          numerical ids, string hashes or the ID_RECENTLY_ADDED constant. If
	 *          null all ids will be affected
	 * @param bandwidthPriority
	 *          this torrent's bandwidth tr_priority_t
	 * @param downloadLimit
	 *          maximum download speed (KBps)
	 * @param downloadLimited
	 *          true if "downloadLimit" is honored
	 * @param filesWanted
	 *          indices of file(s) to download. Empty array for all indices.
	 * @param filesUnwanted
	 *          indices of file(s) to not download. Empty array for all indices.
	 * @param honorsSessionLimits
	 *          true if session upload limits are honored
	 * @param location
	 *          new location of the torrent's content
	 * @param peerLimit
	 *          maximum number of peers
	 * @param priorityHigh
	 *          indices of high-priority file(s). Empty array for all indices.
	 * @param priorityLow
	 *          indices of low-priority file(s). Empty array for all indices.
	 * @param priorityNormal
	 *          indices of normal-priority file(s). Empty array for all indices.
	 * @param seedIdleLimit
	 *          torrent-level number of minutes of seeding inactivity
	 * @param seedIdleMode
	 *          which seeding inactivity to use. See tr_inactvelimit
	 * @param seedRatioLimit
	 *          torrent-level seeding ratio
	 * @param seedRatioMode
	 *          which ratio to use. See tr_ratiolimit
	 * @param trackerAdd
	 *          strings of announce URLs to addd
	 * @param trackerRemove
	 *          ids of trackers to remove
	 * @param trackerReplace
	 *          pairs of <trackerId/new announce URLs>
	 * @param uploadLimit
	 *          maximum upload speed (KBps)
	 * @param uploadLimited
	 *          true if "uploadLimit" is honored
	 * @throws IOException
	 */
	public void setTorrents(Object[] ids, int bandwidthPriority,
			int downloadLimit, boolean downloadLimited, int[] filesWanted,
			int[] filesUnwanted, boolean honorsSessionLimits, String location,
			int peerLimit, int[] priorityHigh, int[] priorityLow,
			int[] priorityNormal, int seedIdleLimit, int seedIdleMode,
			double seedRatioLimit, int seedRatioMode, String[] trackerAdd,
			int[] trackerRemove, TrackerPair[] trackerReplace, int uploadLimit,
			boolean uploadLimited) throws IOException {
		JSONObject obj = new JSONObject();
		if (ids != null && ids.length == 1)
			obj.put("ids", ids[1]);
		else {
			JSONArray ary = new JSONArray();
			if (ids != null) {
				for (int i = 0; i < ids.length; i++)
					ary.put(ids[i]);
			}
			obj.put("ids", ary);
		}
		obj.put("bandwidthPriority", bandwidthPriority);
		obj.put("downloadLimit", downloadLimit);
		obj.put("downloadLimited", downloadLimited);
		if (filesWanted != null) {
			JSONArray ary = new JSONArray();
			for (int i = 0; i < filesWanted.length; i++)
				ary.put(filesWanted[i]);
			obj.put("files-wanted", ary);
		}
		if (filesUnwanted != null) {
			JSONArray ary = new JSONArray();
			for (int i = 0; i < filesUnwanted.length; i++)
				ary.put(filesUnwanted[i]);
			obj.put("files-unwanted", ary);
		}
		obj.put("honorsSessionLimits", honorsSessionLimits);
		if (location != null)
			obj.put("location", location);
		obj.put("peer-limit", peerLimit);
		if (priorityHigh != null) {
			JSONArray ary = new JSONArray();
			for (int i = 0; i < priorityHigh.length; i++)
				ary.put(priorityHigh[i]);
			obj.put("priority-high", ary);
		}
		if (priorityLow != null) {
			JSONArray ary = new JSONArray();
			for (int i = 0; i < priorityLow.length; i++)
				ary.put(priorityLow[i]);
			obj.put("priority-low", ary);
		}
		if (priorityNormal != null) {
			JSONArray ary = new JSONArray();
			for (int i = 0; i < priorityNormal.length; i++)
				ary.put(priorityNormal[i]);
			obj.put("priority-normal", ary);
		}

		obj.put("seedIdleLimit", seedIdleLimit);
		obj.put("seedIdleMode", seedIdleMode);
		obj.put("seedRatioLimit", seedRatioLimit);
		obj.put("seedRatioMode", seedRatioMode);
		if (trackerAdd != null) {
			JSONArray ary = new JSONArray();
			for (int i = 0; i < trackerAdd.length; i++)
				ary.put(trackerAdd[i]);
			obj.put("trackerAdd", ary);
		}
		if (trackerRemove != null) {
			JSONArray ary = new JSONArray();
			for (int i = 0; i < trackerRemove.length; i++)
				ary.put(trackerRemove[i]);
			obj.put("trackerRemove", ary);
		}
		if (trackerReplace != null) {
			JSONArray ary = new JSONArray();
			for (int i = 0; i < trackerReplace.length; i++) {
				JSONArray ary2 = new JSONArray();
				ary2.put(trackerReplace[i].id);
				ary2.put(trackerReplace[i].newURL);
				ary.put(ary2);
			}
			obj.put("trackerReplace", ary);
		}
		obj.put("uploadLimit", uploadLimit);
		obj.put("uploadLimited", uploadLimited);

		sendCommand("torrent-set", obj);
	}

	/**
	 * Removes given torrents
	 * 
	 * @param ids
	 *          numerical ids, string hashes or the ID_RECENTLY_ADDED constant
	 * @param deleteLocalData
	 * @throws IOException
	 */
	public void removeTorrents(Object[] ids, boolean deleteLocalData)
			throws IOException {
		if (ids == null)
			throw new NullPointerException("At least one id is required");
		JSONObject obj = new JSONObject();
		if (ids.length == 1)
			obj.put("ids", ids[1]);
		else {
			JSONArray ary = new JSONArray();
			for (int i = 0; i < ids.length; i++)
				ary.put(ids[i]);
			obj.put("ids", ary);
		}
		obj.put("delete-local-data", deleteLocalData);
		sendCommand("torrent-remove", obj);
	}

	/**
	 * 
	 * @param ids
	 *          numerical ids, string hashes or the ID_RECENTLY_ADDED constant
	 * @param location
	 * @param move
	 * @throws IOException
	 */
	public void moveTorrents(Object[] ids, String location, boolean move)
			throws IOException {
		if (ids == null)
			throw new NullPointerException("At least one id is required");
		JSONObject obj = new JSONObject();
		if (ids.length == 1)
			obj.put("ids", ids[0]);
		else {
			JSONArray ary = new JSONArray();
			for (int i = 0; i < ids.length; i++)
				ary.put(ids[i]);
			obj.put("ids", ary);
		}
		obj.put("location", location);
		obj.put("move", move);
		sendCommand("torrent-set-location", obj);
	}

	private static SessionField[] SET_SESSION_DISALLOWED = new TransmissionSession.SessionField[] {
			SessionField.blocklistSize, SessionField.configDir,
			SessionField.rpcVersion, SessionField.rpcVersionMinimum,
			SessionField.version };

	/**
	 * 
	 * @param pairs
	 *          one or more pair of TransmissionSession.SessionField and value.
	 *          All SessionFields are valid, except: blocklistSize, configDir,
	 *          rpcVersion, rpcVersionMinimum, and version. An error will be
	 *          surfaced if those are included
	 * @throws IOException
	 */
	public void setSession(SessionPair... pairs) throws IOException {
		if (pairs == null)
			throw new NullPointerException("At least one pair is required");
		JSONObject obj = new JSONObject();
		for (int i = 0; i < pairs.length; i++) {
			SessionField curr = pairs[i].field;
			for (int j = 0; j < SET_SESSION_DISALLOWED.length; j++) {
				if (SET_SESSION_DISALLOWED[j] == curr)
					throw new IllegalArgumentException("Disallowed: " + curr.name());
			}
			obj.put(TransmissionSession.FIELD_NAMES[curr.ordinal()], pairs[i].value);
		}
		sendCommand("session-set", obj);
	}

	public Map<SessionField, Object> getSession() throws IOException {
		JSONObject result = sendCommand("session-get", null);
		Map<SessionField, Object> valByField = new HashMap<SessionField, Object>();
		for (int i = 0; i < TransmissionSession.FIELD_NAMES.length; i++) {
			String curr = TransmissionSession.FIELD_NAMES[i];
			Object val = result.get(curr);
			valByField.put(SessionField.values()[i], val);
		}
		return valByField;
	}

	/**
	 * @return session status
	 * @throws IOException
	 */
	public SessionStatus getSessionStats() throws IOException {
		return new SessionStatus(sendCommand("session-stats", null));
	}

	public int updateBlocklist() throws IOException {
		return sendCommand("session-stats", null).getInt("blocklist-size");
	}

	/**
	 * This method tests to see if your incoming peer port is accessible from the
	 * outside world.
	 * 
	 * @return true if incoming peer port is accessible from outside
	 * @throws IOException
	 */
	public boolean isPortOpen() throws IOException {
		return sendCommand("port-test", null).getBoolean("port-is-open");
	}

}