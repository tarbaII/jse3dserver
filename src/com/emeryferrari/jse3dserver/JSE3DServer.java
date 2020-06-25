package com.emeryferrari.jse3dserver;
import java.net.*;
import java.io.*;
import java.util.*;
import com.emeryferrari.jse3d.network.*;
import com.emeryferrari.jse3d.obj.*;
import java.security.*;
import com.emeryferrari.rsacodec.*;
import java.security.spec.*;
import javax.crypto.*;
import javax.crypto.spec.*;
public class JSE3DServer {
	private boolean stop = false;
	private int count = 0;
	private static final JSE3DServer CLASS_OBJ = new JSE3DServer();
	private int sceneID;
	private Scene scene;
	private ArrayList<String> users = new ArrayList<String>();
	private JSE3DServer() {}
	public static void main(String[] args) {
		for (int i = 0; i < 1000; i++) {
			CLASS_OBJ.users.add(null);
		}
		Thread.currentThread().setName("listener");
		int port = 5107;
		int sceneID = 0;
		if (args.length == 1) {
			try {
				port = Integer.parseInt(args[0]);
			} catch (NumberFormatException ex) {
				System.out.println("Incorrectly formatted port. Defaulting to 5107...");
				port = 5107;
			}
		} else if (args.length == 2) {
			try {
				port = Integer.parseInt(args[0]);
			} catch (NumberFormatException ex) {
				System.out.println("Incorrectly formatted port. Defaulting to 5107...");
				port = 5107;
			}
			try {
				sceneID = Integer.parseInt(args[1]);
			} catch (NumberFormatException ex) {
				System.out.println("Incorrectly formatted scene ID. Defaulting to 0...");
				sceneID = 0;
			}
		} else if (args.length == 0) {
			System.out.println("Defaulting port to 5107...");
			System.out.println("Defaulting scene ID to 0...");
		} else {
			printUsage();
		}
		System.out.println("Starting jse3d server on [127.0.0.1:" + port + "].");
		CLASS_OBJ.start(port, sceneID);
	}
	private static void printUsage() {
		System.out.println("Usage: java JSE3DServer port scene_id");
		System.exit(1);
	}
	public void start(int port, int sceneID) {
		File sceneFile = new File("scenes/scn" + sceneID + ".jscn");
		if (sceneFile.exists()) {
			try {
				scene = JSE3DSerializer.loadScene(sceneFile);
			} catch (IOException ex) {
				handleException(ex);
				System.out.println("Error loading " + sceneFile.getAbsolutePath() + ". Defaulting scene to empty scene.");
			} catch (ClassNotFoundException ex) {
				handleException(ex);
				System.exit(2);
			}
		} else {
			System.out.println(sceneFile.getAbsolutePath() + " was not found. Creating file...");
			try {
				sceneFile.createNewFile();
				Object3D[] objects = {};
				Scene tmp = new Scene(objects, 5.0);
				JSE3DSerializer.saveScene(tmp, sceneFile);
			} catch (IOException ex) {
				handleException(ex);
				System.exit(3);
			}
		}
		Thread consoleReader = new Thread(new ConsoleReader());
		consoleReader.setName("console-input");
		consoleReader.start();
		Thread autosave = new Thread(new Autosave());
		autosave.setName("autosave");
		autosave.start();
		ServerSocket server = null;
		try {
			server = new ServerSocket(port);
			System.out.println("Server started.");
		} catch (IOException ex) {
			handleException(ex);
			System.out.println("Server was not started successfully.");
			System.exit(1);
		}
		while (!stop) {
			try {
				if (server != null) {
					Socket socket = server.accept();
					System.out.println("Found client at " + socket.getInetAddress().getHostAddress() + ":" + socket.getPort() + ". Client ID: " + count + ".");
					Thread client = new Thread(new ClientHandler(socket, count));
					client.setName("client-handler-" + count);
					count++;
					client.start();
				}
			} catch (IOException ex) {
				handleException(ex);
			}
		}
	}
	public static void handleException(Exception ex) {
		if (!(ex.getMessage().equalsIgnoreCase("socket closed") || ex.getMessage().equalsIgnoreCase("connection reset"))) {
			System.out.println("  ** EXCEPTION THROWN **");
			System.out.println(String.valueOf(ex.getClass()).split(" ")[1] + " occurred in thread \"" + Thread.currentThread().getName() + "\":");
			System.out.println("Cause: " + ex.getMessage());
			System.out.println();
		}
	}
	public class ConsoleReader implements Runnable {
		public void run() {
			try {
				BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
				String line;
				while (!stop) {
					line = reader.readLine();
					if (line != null) {
						handleCommand(line);
					}
				}
				System.exit(0);
			} catch (IOException ex) {
				handleException(ex);
			}
		}
	}
	public void handleCommand(String command) {
		if (command.equalsIgnoreCase("stop")) {
			stop = true;
			System.out.println("Stopping server...");
		} else {
			System.out.println("Unknown command.");
		}
	}
	public class ClientHandler implements Runnable {
		private Socket socket;
		private int clientID;
		@SuppressWarnings("unused")
		private String username;
		public ClientHandler(Socket socket, int clientID) {
			this.socket = socket;
			this.clientID = clientID;
		}
		public void run() {
			String username = null;
			try {
				ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
				ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
				KeyPair sessionPair = RSAGenerator.generateKeyPair(2048);
				oos.writeObject(sessionPair.getPublic());
				Object keyObj = ois.readObject();
				byte[] pbeEnc = null;
				if (keyObj instanceof byte[]) {
					pbeEnc = (byte[]) keyObj;
				} else {
					socket.close();
					System.out.println("[Server thread: " + Thread.currentThread().getName() + "]: Rejected incoming user " + username + " with client ID " + clientID + " as the secure handshake has failed.");
				}
				byte[] pbeBytes = null;
				if (pbeEnc == null) {
					socket.close();
					System.out.println("[Server thread: " + Thread.currentThread().getName() + "]: Rejected incoming user " + username + " with client ID " + clientID + " as the secure handshake has failed.");
				} else {
					pbeBytes = RSACodec.decrypt(pbeEnc, sessionPair.getPrivate());
				}
				KeySpec ks = new PBEKeySpec(new String(pbeBytes, "UTF-8").toCharArray());
				SecretKeyFactory skf = SecretKeyFactory.getInstance("PBEWithMD5AndDES");
				SecretKey key = skf.generateSecret(ks);
				Cipher encrypt = Cipher.getInstance("PBEWithMD5AndDES");
				Cipher decrypt = Cipher.getInstance("PBEWithMD5AndDES");
				encrypt.init(Cipher.ENCRYPT_MODE, key);
				decrypt.init(Cipher.DECRYPT_MODE, key);
				CipherOutputStream cos = new CipherOutputStream(socket.getOutputStream(), encrypt);
				CipherInputStream cis = new CipherInputStream(socket.getInputStream(), decrypt);
				ois = new ObjectInputStream(cis);
				oos = new ObjectOutputStream(cos);
				username = (String) ois.readObject();
				String version = (String) ois.readObject();
				if (!version.equals(JSE3DServerConst.API_VERSION)) {
					socket.close();
					System.out.println("[Server thread: " + Thread.currentThread().getName() + "]: Rejected incoming user " + username + " with client ID " + clientID + " as the client version is not equal to the server version.");
				}
				if (users.contains(username)) {
					oos.writeObject(new Disconnect(DisconnectType.USERNAME_TAKEN));
					oos.flush();
					System.out.println("[Server thread: " + Thread.currentThread().getName() + "]: Rejected incoming user " + username + " with client ID " + clientID + " as the username is already taken.");
					socket.close();
				} else if (username.equals("")) {
					oos.writeObject(new Disconnect(DisconnectType.USERNAME_INVALID));
					oos.flush();
					System.out.println("[Server thread: " + Thread.currentThread().getName() + "]: Rejected incoming user with client ID " + clientID + " as the username is not valid.");
					socket.close();
				} else {
					users.set(clientID, username);
				}
				System.out.print("[Server thread: " + Thread.currentThread().getName() + "]: Uploading scene to user " + username + "... ");
				oos.writeObject(scene);
				oos.flush();
				System.out.println("Done!");
				Thread receiver = new Thread(new Receiver(socket, clientID, username, ois));
				receiver.setName("client-receiver-" + clientID);
				receiver.start();
				Thread monitor = new Thread(new SceneMonitor(socket, oos));
				monitor.setName("client-monitor-" + clientID);
				monitor.start();
				while (!socket.isClosed()) {
					try {
						Thread.sleep(100);
					} catch (InterruptedException ex) {
						handleException(ex);
					}
				}
				oos.flush();
				socket.close();
				if (!(username == null)) {
					users.set(clientID, null);
				}
				System.out.println("[Server thread: "+Thread.currentThread().getName() + "]: Client with client ID " + clientID + " has disconnected.");
			} catch (IOException ex) {
				System.out.println("[Server thread: "+Thread.currentThread().getName() + "]: Client with client ID " + clientID + " has disconnected.");
				if (!(username == null)) {
					users.set(clientID, null);
				}
				try {socket.close();} catch (IOException ex2) {}
			} catch (ClassNotFoundException ex) {
				handleException(ex);
				if (!(username == null)) {
					users.set(clientID, null);
				}
				try {socket.close();} catch (IOException ex2) {}
				System.out.println("Class not found. Install required classes and restart server. Quitting...");
				System.exit(10);
			} catch (GeneralSecurityException ex) {
				System.out.println("Security exception in client " + clientID + ".");
				System.out.println("[Server thread: "+Thread.currentThread().getName() + "]: Client with client ID " + clientID + " has disconnected.");
				try {socket.close();} catch (IOException ex2) {}
			}
		}
	}
	public class SceneMonitor implements Runnable {
		private Socket socket;
		private ObjectOutputStream oos;
		public SceneMonitor(Socket socket, ObjectOutputStream oos) {
			this.socket = socket;
			this.oos = oos;
		}
		public void run() {
			boolean shouldRun1 = true;
			if (socket.isClosed()) {
				shouldRun1 = false;
			}
			if (shouldRun1) {
				if (oos != null) {
					boolean shouldRun2 = true;
					while (!stop && shouldRun2) {
						if (socket.isClosed()) {
							shouldRun2 = false;
						} else {
							try {
								Scene temp = scene;
								while (temp.equals(scene)) {
									try {Thread.sleep(16);} catch (InterruptedException ex) {}
								}
								oos.writeObject(scene);
								oos.flush();
							} catch (IOException ex) {
								handleException(ex);
							}
						}
					}
				}
			}
		}
	}
	public class Receiver implements Runnable {
		private Socket socket;
		private int clientID;
		private ObjectInputStream ois;
		public Receiver(Socket socket, int clientID, String username, ObjectInputStream ois) {
			this.socket = socket;
			this.clientID = clientID;
			this.ois = ois;
		}
		public void run() {
			boolean shouldRun = true;
			while (!stop && shouldRun) {
				if (socket.isClosed()) {
					shouldRun = false;
				} else {
					try {
						Object object;
						while ((object = ois.readObject()) != null) {
							if (object instanceof Scene) {
								System.out.println("[Server thread: " + Thread.currentThread().getName() + "]: Client with client ID " + clientID + " changed the current scene.");
								scene = (Scene) object;
							} else if (object instanceof Disconnect) {
								socket.close();
							}
						}
					} catch (IOException ex) {
						handleException(ex);
						try {
							socket.close();
						} catch (IOException ex2) {
							handleException(ex2);
						}
					} catch (ClassNotFoundException ex) {
						handleException(ex);
						System.exit(2);
					}
				}
			}
		}
	}
	public class Autosave implements Runnable {
		public void run() {
			while (!stop) {
				try {
					JSE3DSerializer.saveScene(new File("scenes/scn" +sceneID + ".jscn"), scene);
				} catch (IOException ex) {
					handleException(ex);
				}
				try {
					Thread.sleep(JSE3DServerConst.AUTOSAVE_INTERVAL);
				} catch (InterruptedException ex) {
					handleException(ex);
				}
			}
		}
	}
}