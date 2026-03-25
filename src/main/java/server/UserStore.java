package server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;


// Thread-safe user store with file-backed persistence.
 // Stores username + salted SHA-256 password hashes
public class UserStore {
    private static final Logger LOG = Logger.getLogger(UserStore.class.getName());

    private final Path filePath;
    private final ConcurrentHashMap<String, String> users = new ConcurrentHashMap<>();

    public UserStore(String filename) {
        this.filePath = Paths.get(filename);
        load();
    }

    // Register a new user. Returns false if username already taken.
     
    public synchronized boolean register(String username, String password) {
        if (users.containsKey(username.toLowerCase())) {
            return false;
        }
        String hashed = hashPassword(password);
        users.put(username.toLowerCase(), hashed);
        persist();
        LOG.info("[" + Thread.currentThread().getName() + "] New user registered: " + username);
        return true;
    }

  
     //Authenticate a user. Returns true if credentials match.
     
    public synchronized boolean authenticate(String username, String password) {
        String stored = users.get(username.toLowerCase());
        if (stored == null) {
            return false;
        }
        return verifyPassword(password, stored);
    }

    public synchronized boolean userExists(String username) {
        return users.containsKey(username.toLowerCase());
    }

    //Delete a user account. Returns true if the user existed and was removed.
    
    public synchronized boolean deleteUser(String username, String password) {
        if (!authenticate(username, password)) {
            return false;
        }
        users.remove(username.toLowerCase());
        persist();
        LOG.info("[" + Thread.currentThread().getName() + "] User deleted: " + username);
        return true;
    }

    // --- Password hashing with salt ----
// Handles password hashing and verification using SHA-256 with a random salt (tastes salty \(-W-)/ )

    private String hashPassword(String password) {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] hash = sha256(salt, password);
        return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
    }

    private boolean verifyPassword(String password, String stored) {
        String[] parts = stored.split(":", 2);
        if (parts.length != 2) return false;
        byte[] salt = Base64.getDecoder().decode(parts[0]);
        byte[] expectedHash = Base64.getDecoder().decode(parts[1]);
        byte[] actualHash = sha256(salt, password);
        if (expectedHash.length != actualHash.length) return false;
        int diff = 0;
        for (int i = 0; i < expectedHash.length; i++) {
            diff |= expectedHash[i] ^ actualHash[i];
        }
        return diff == 0;
    }

    private byte[] sha256(byte[] salt, String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            return md.digest(password.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // --- File persistence ----
// Loaads users from the file into memory 

    private void load() {
        if (!Files.exists(filePath)) return;
        try (BufferedReader br = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int sep = line.indexOf('|');
                if (sep > 0) {
                    users.put(line.substring(0, sep), line.substring(sep + 1));
                }
            }
            LOG.info("Loaded " + users.size() + " users from " + filePath);
        } catch (IOException e) {
            LOG.warning("Could not load user file: " + e.getMessage());
        }
    }

    private void persist() {
        try (BufferedWriter bw = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (var entry : users.entrySet()) {
                bw.write(entry.getKey() + "|" + entry.getValue());
                bw.newLine();
            }
        } catch (IOException e) {
            LOG.warning("Could not save user file: " + e.getMessage());
        }
    }
}
