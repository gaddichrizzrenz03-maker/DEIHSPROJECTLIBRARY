import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class DEIHSLibrary extends JFrame {
    private static final String DATA_FILE = "deihs_accounts.dat";
    private static final String BOOKS_FILE = "book.txt";
    private static final String TRANSACTIONS_FILE = "deihs_transactions.dat";
    private static final String DATABASE_FILE_NAME = "deihs_library.db";
    private static final File DATABASE_FILE = resolveDatabaseFile();
    private static final String DB_URL = System.getenv().getOrDefault("DEIHS_DB_URL", "jdbc:sqlite:" + DATABASE_FILE.getAbsolutePath());
    private boolean databaseAvailable;
    
    private static final Color NAVY = new Color(24, 37, 65);
    private static final Color BLUE = new Color(63, 111, 220);
    private static final Color BG = new Color(245, 247, 251);
    private static final Color TEXT = new Color(43, 52, 69);
    private static final Color GREEN = new Color(35, 150, 105);
    private static final Color RED = new Color(205, 65, 65);

    private final String[] USER_ROLES = {"Student", "Teacher", "Principal", "Staff"};

    private static final String[][] DEFAULT_ACCOUNTS = {
        {"System Administrator", "123456789", "admin@deihs.edu", "Admin", "Admin@12345"},
        {"Gaddi, Chrizz Renz B.", "109337160776", "chrizz@student.deihs.edu", "Student", "ploofit"},
        {"Sir Eunico", "TCH001", "teacher@deihs.edu", "Teacher", "Teacher@123"},
        {"Sample Principal", "PRN001", "principal@deihs.edu", "Principal", "Principal@123"},
        {"Sample Staff", "STF001", "staff@deihs.edu", "Staff", "Staff@123"},
        {"Test Student", "123456789012", "test.student@deihs.edu", "Student", "Student@123"}
    };
    private ArrayList<Account> accounts = new ArrayList<Account>();
    private ArrayList<Book> books = new ArrayList<Book>();
    private ArrayList<Transaction> transactions = new ArrayList<Transaction>();
    private Account currentAccount;

    private CardLayout cardLayout;
    private JPanel root;

    private JComboBox<String> loginRole;
    private JTextField loginId;
    private JPasswordField loginPassword;

    private JComboBox<String> registerRole;
    private JTextField registerName;
    private JTextField registerId;
    private JTextField registerEmail;
    private JPasswordField registerPassword;
    private JPasswordField registerConfirm;

    private JPanel dashboardPanel;

    public static class Account implements Serializable {
        private static final long serialVersionUID = 1L;
        String name, id, email, role, salt, passwordHash, status;

        Account(String name, String id, String email, String role, String password) {
            this.name = name;
            this.id = id;
            this.email = email;
            this.role = role;
            this.salt = UUID.randomUUID().toString();
            this.passwordHash = hashPassword(password, salt);
            this.status = "PENDING";
        }
    }

    public static class Book implements Serializable {
        private static final long serialVersionUID = 1L;
        String bookId, title, author, category;
        int totalCopies, availableCopies;

        Book(String bookId, String title, String author, String category, int totalCopies) {
            this(bookId, title, author, category, totalCopies, totalCopies);
        }

        Book(String bookId, String title, String author, String category, int totalCopies, int availableCopies) {
            this.bookId = bookId;
            this.title = title;
            this.author = author;
            this.category = category;
            this.totalCopies = totalCopies;
            this.availableCopies = availableCopies;
        }
    }

    public static class Transaction implements Serializable {
        private static final long serialVersionUID = 1L;
        String transactionId, userId, userName, bookId, bookTitle, type, status, timestamp;

        Transaction(String userId, String userName, String bookId, String bookTitle, String type) {
            this.transactionId = "TXN-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
            this.userId = userId;
            this.userName = userName;
            this.bookId = bookId;
            this.bookTitle = bookTitle;
            this.type = type; // "BORROW", "RETURN", "BOOK_REQUEST"
            this.status = "PENDING"; // "PENDING", "APPROVED", "COMPLETED", "REJECTED"
            this.timestamp = new java.util.Date().toString();
        }
    }

    public DEIHSLibrary() {
        super("DEIHS Library Management System");
        databaseAvailable = initializeDatabase();
        loadData();
        seedDemoData();

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(850, 620));
        setSize(1000, 700);
        setLocationRelativeTo(null);

        cardLayout = new CardLayout();
        root = new JPanel(cardLayout);
        root.add(buildLoginPage(), "LOGIN");
        root.add(buildRegistrationPage(), "REGISTER");
        setContentPane(root);
        cardLayout.show(root, "LOGIN");
    }

    // -------------------- PASSWORD HASHING --------------------

    private static String hashPassword(String password, String salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(
                password.toCharArray(),
                salt.getBytes(StandardCharsets.UTF_8),
                65536,
                256
            );
            SecretKeyFactory factory =
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = factory.generateSecret(spec).getEncoded();
            spec.clearPassword();
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to secure password.", ex);
        }
    }

    private boolean passwordMatches(Account account, String password) {
        String entered = hashPassword(password, account.salt);
        return MessageDigest.isEqual(
            entered.getBytes(StandardCharsets.UTF_8),
            account.passwordHash.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static File resolveDatabaseFile() {
        File fromWorkingDir = searchForDatabase(new File(System.getProperty("user.dir", ".")).getAbsoluteFile());
        if (fromWorkingDir != null) {
            return fromWorkingDir;
        }
        File fromClasses = searchForDatabase(classDirectory());
        if (fromClasses != null) {
            return fromClasses;
        }
        return new File(System.getProperty("user.dir", "."), DATABASE_FILE_NAME).getAbsoluteFile();
    }

    private static File classDirectory() {
        try {
            File location = new File(DEIHSLibrary.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return location.isDirectory() ? location : location.getParentFile();
        } catch (Exception ex) {
            return null;
        }
    }

    private static File searchForDatabase(File start) {
        File dir = start;
        for (int depth = 0; dir != null && depth < 8; depth++) {
            File candidate = new File(dir, DATABASE_FILE_NAME);
            if (candidate.isFile()) {
                return candidate;
            }
            if (new File(dir, "DEIHSLibrary.java").isFile()) {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    private static File connectedDatabaseFile() {
        String prefix = "jdbc:sqlite:";
        return new File(DB_URL.startsWith(prefix) ? DB_URL.substring(prefix.length()) : DB_URL).getAbsoluteFile();
    }

    private boolean initializeDatabase() {
        try {
            Class.forName("org.sqlite.JDBC");
            try (Connection conn = getConnection()) {
                if (conn != null) {
                    createDatabaseTables(conn);
                    System.out.println("SQLite database connected: " + connectedDatabaseFile().getAbsolutePath());
                    return true;
                }
            }
        } catch (ClassNotFoundException ex) {
            System.err.println("SQLite JDBC driver not found. Add lib/sqlite-jdbc-3.54.1.0.jar to the classpath.");
        } catch (SQLException ex) {
            System.err.println("SQLite unavailable. Falling back to local files: " + ex.getMessage());
        }
        return false;
    }

    private String integrityCheck(Connection conn) {
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("PRAGMA integrity_check")) {
            return rs.next() ? rs.getString(1) : "unknown";
        } catch (SQLException ex) {
            return "unavailable (" + ex.getMessage() + ")";
        }
    }

    private void backupDatabaseFile() {
        try {
            File source = connectedDatabaseFile();
            File backup = new File(source.getParentFile(),
                source.getName() + ".broken-" + System.currentTimeMillis());
            java.nio.file.Files.copy(source.toPath(), backup.toPath());
            System.err.println("Damaged database backed up to " + backup.getAbsolutePath());
        } catch (Exception ex) {
            System.err.println("Could not back up damaged database: " + ex.getMessage());
        }
    }

    private int repairRows(Connection conn) throws SQLException {
        int repaired = 0;
        try (Statement stmt = conn.createStatement()) {
            repaired += stmt.executeUpdate("DELETE FROM library_books WHERE bookId IS NULL OR TRIM(bookId) = ''");
            repaired += stmt.executeUpdate("DELETE FROM library_transactions WHERE transactionId IS NULL OR TRIM(transactionId) = ''");
            repaired += stmt.executeUpdate("UPDATE library_books SET title = bookId WHERE title IS NULL OR TRIM(title) = ''");
            repaired += stmt.executeUpdate("UPDATE library_books SET author = 'Unknown' WHERE author IS NULL OR TRIM(author) = ''");
            repaired += stmt.executeUpdate("UPDATE library_books SET category = 'General' WHERE category IS NULL OR TRIM(category) = ''");
            repaired += stmt.executeUpdate("UPDATE library_books SET totalCopies = 0 WHERE totalCopies IS NULL OR totalCopies < 0");
            repaired += stmt.executeUpdate("UPDATE library_books SET availableCopies = totalCopies " +
                "WHERE availableCopies IS NULL OR availableCopies < 0 OR availableCopies > totalCopies");
            repaired += stmt.executeUpdate("UPDATE library_accounts SET role = 'Student' WHERE role IS NULL OR TRIM(role) = ''");
            repaired += stmt.executeUpdate("UPDATE library_accounts SET status = 'PENDING' WHERE status IS NULL OR TRIM(status) = ''");
            repaired += stmt.executeUpdate("UPDATE library_transactions SET status = 'PENDING' WHERE status IS NULL OR TRIM(status) = ''");
            stmt.execute("PRAGMA optimize");
        }
        return repaired;
    }

    private void ensureColumn(Connection conn, String table, String column, String definition) throws SQLException {
        boolean found = false;
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            try (Statement alter = conn.createStatement()) {
                alter.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            }
            System.out.println("Database schema repaired: added " + table + "." + column);
        }
    }

    private void ensureSchema(Connection conn) throws SQLException {
        ensureColumn(conn, "library_accounts", "name", "VARCHAR(255)");
        ensureColumn(conn, "library_accounts", "email", "VARCHAR(255)");
        ensureColumn(conn, "library_accounts", "role", "VARCHAR(50)");
        ensureColumn(conn, "library_accounts", "salt", "VARCHAR(255)");
        ensureColumn(conn, "library_accounts", "passwordHash", "VARCHAR(255)");
        ensureColumn(conn, "library_accounts", "status", "VARCHAR(20)");
        ensureColumn(conn, "library_books", "title", "VARCHAR(255)");
        ensureColumn(conn, "library_books", "author", "VARCHAR(255)");
        ensureColumn(conn, "library_books", "category", "VARCHAR(100)");
        ensureColumn(conn, "library_books", "totalCopies", "INT");
        ensureColumn(conn, "library_books", "availableCopies", "INT");
        ensureColumn(conn, "library_transactions", "userId", "VARCHAR(100)");
        ensureColumn(conn, "library_transactions", "userName", "VARCHAR(255)");
        ensureColumn(conn, "library_transactions", "bookId", "VARCHAR(100)");
        ensureColumn(conn, "library_transactions", "bookTitle", "VARCHAR(255)");
        ensureColumn(conn, "library_transactions", "type", "VARCHAR(50)");
        ensureColumn(conn, "library_transactions", "status", "VARCHAR(20)");
        ensureColumn(conn, "library_transactions", "timestamp", "VARCHAR(255)");
    }

    private void backupDatabaseNow() {
        File db = connectedDatabaseFile();
        try {
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
            File backup = new File(db.getParentFile(), db.getName() + ".backup-" + stamp);
            java.nio.file.Files.copy(db.toPath(), backup.toPath());
            showMessage("Backup created:\n" + backup.getAbsolutePath());
        } catch (Exception ex) {
            showError("Could not create backup:\n" + ex.getMessage());
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL);
    }

    private void createDatabaseTables(Connection conn) throws SQLException {
        String integrity = integrityCheck(conn);
        if (!"ok".equalsIgnoreCase(integrity)) {
            backupDatabaseFile();
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DROP TABLE IF EXISTS library_accounts");
                stmt.executeUpdate("DROP TABLE IF EXISTS library_books");
                stmt.executeUpdate("DROP TABLE IF EXISTS library_transactions");
            }
            System.err.println("Database was damaged (" + integrity + "). Backup saved and tables rebuilt.");
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS library_accounts (" +
                "name VARCHAR(255), " +
                "id VARCHAR(100) PRIMARY KEY, " +
                "email VARCHAR(255), " +
                "role VARCHAR(50), " +
                "salt VARCHAR(255), " +
                "passwordHash VARCHAR(255), " +
                "status VARCHAR(20)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS library_books (" +
                "bookId VARCHAR(100) PRIMARY KEY, " +
                "title VARCHAR(255), " +
                "author VARCHAR(255), " +
                "category VARCHAR(100), " +
                "totalCopies INT, " +
                "availableCopies INT" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS library_transactions (" +
                "transactionId VARCHAR(100) PRIMARY KEY, " +
                "userId VARCHAR(100), " +
                "userName VARCHAR(255), " +
                "bookId VARCHAR(100), " +
                "bookTitle VARCHAR(255), " +
                "type VARCHAR(50), " +
                "status VARCHAR(20), " +
                "timestamp VARCHAR(255)" +
                ")"
            );
        }
        ensureSchema(conn);
        int repaired = repairRows(conn);
        System.out.println("Database check: integrity " + integrityCheck(conn) + ", rows repaired = " + repaired);
    }

    // -------------------- DATA PERSISTENCE --------------------

    @SuppressWarnings("unchecked")
    private void loadData() {
        if (databaseAvailable) {
            accounts = loadAccountsFromDatabase();
            books = loadBooksFromDatabase();
            transactions = loadTransactionsFromDatabase();
            return;
        }

        // Load Accounts
        File accFile = new File(DATA_FILE);
        if (accFile.exists()) {
            try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(accFile))) {
                Object data = in.readObject();
                if (data instanceof ArrayList<?>) accounts = (ArrayList<Account>) data;
            } catch (Exception ignored) { accounts = new ArrayList<>(); }
        }

        // Load Books from book.txt
        books = new ArrayList<>();
        File bookFile = new File(BOOKS_FILE);
        if (bookFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(bookFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    String[] parts = line.split("\\|");
                    if (parts.length >= 5) {
                        String id = parts[0].trim();
                        String title = parts[1].trim();
                        String author = parts[2].trim();
                        String category = parts[3].trim();
                        int total = Integer.parseInt(parts[4].trim());
                        int available = (parts.length >= 6) ? Integer.parseInt(parts[5].trim()) : total;
                        books.add(new Book(id, title, author, category, total, available));
                    }
                }
            } catch (Exception ignored) { books = new ArrayList<>(); }
        }

        // Load Transactions
        File txnFile = new File(TRANSACTIONS_FILE);
        if (txnFile.exists()) {
            try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(txnFile))) {
                Object data = in.readObject();
                if (data instanceof ArrayList<?>) transactions = (ArrayList<Transaction>) data;
            } catch (Exception ignored) { transactions = new ArrayList<>(); }
        }
    }

    private ArrayList<Account> loadAccountsFromDatabase() {
        ArrayList<Account> loaded = new ArrayList<>();
        String sql = "SELECT name, id, email, role, salt, passwordHash, status FROM library_accounts";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Account account = new Account(rs.getString("name"), rs.getString("id"), rs.getString("email"), rs.getString("role"), "");
                account.salt = rs.getString("salt");
                account.passwordHash = rs.getString("passwordHash");
                account.status = rs.getString("status");
                loaded.add(account);
            }
        } catch (SQLException ex) {
            System.err.println("Unable to load accounts from SQLite: " + ex.getMessage());
        }
        return loaded;
    }

    private ArrayList<Book> loadBooksFromDatabase() {
        ArrayList<Book> loaded = new ArrayList<>();
        String sql = "SELECT bookId, title, author, category, totalCopies, availableCopies FROM library_books";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String id = rs.getString("bookId");
                String title = rs.getString("title");
                String author = rs.getString("author");
                String category = rs.getString("category");
                int total = rs.getInt("totalCopies");
                int available = rs.getInt("availableCopies");
                loaded.add(new Book(id, title, author, category, total, available));
            }
        } catch (SQLException ex) {
            System.err.println("Unable to load books from SQLite: " + ex.getMessage());
        }
        return loaded;
    }

    private ArrayList<Transaction> loadTransactionsFromDatabase() {
        ArrayList<Transaction> loaded = new ArrayList<>();
        String sql = "SELECT transactionId, userId, userName, bookId, bookTitle, type, status, timestamp FROM library_transactions";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Transaction txn = new Transaction(rs.getString("userId"), rs.getString("userName"), rs.getString("bookId"), rs.getString("bookTitle"), rs.getString("type"));
                txn.transactionId = rs.getString("transactionId");
                txn.status = rs.getString("status");
                txn.timestamp = rs.getString("timestamp");
                loaded.add(txn);
            }
        } catch (SQLException ex) {
            System.err.println("Unable to load transactions from SQLite: " + ex.getMessage());
        }
        return loaded;
    }

    private boolean saveAccounts() {
        if (databaseAvailable) {
            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement clear = conn.prepareStatement("DELETE FROM library_accounts")) {
                        clear.executeUpdate();
                    }
                    String sql = "INSERT INTO library_accounts (name, id, email, role, salt, passwordHash, status) VALUES (?, ?, ?, ?, ?, ?, ?)";
                    try (PreparedStatement insert = conn.prepareStatement(sql)) {
                        for (Account account : accounts) {
                            insert.setString(1, account.name);
                            insert.setString(2, account.id);
                            insert.setString(3, account.email);
                            insert.setString(4, account.role);
                            insert.setString(5, account.salt);
                            insert.setString(6, account.passwordHash);
                            insert.setString(7, account.status);
                            insert.addBatch();
                        }
                        insert.executeBatch();
                    }
                    conn.commit();
                    return true;
                } catch (SQLException ex) {
                    conn.rollback();
                    throw ex;
                }
            } catch (SQLException ex) {
                showError("Could not save account data to the database:\n" + ex.getMessage());
                return false;
            }
        }

        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(DATA_FILE))) {
            out.writeObject(accounts);
            return true;
        } catch (IOException ex) {
            showError("Could not save account data:\n" + ex.getMessage());
            return false;
        }
    }

    private boolean saveBooks() {
        if (databaseAvailable) {
            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement clear = conn.prepareStatement("DELETE FROM library_books")) {
                        clear.executeUpdate();
                    }
                    String sql = "INSERT INTO library_books (bookId, title, author, category, totalCopies, availableCopies) VALUES (?, ?, ?, ?, ?, ?)";
                    try (PreparedStatement insert = conn.prepareStatement(sql)) {
                        for (Book b : books) {
                            insert.setString(1, b.bookId);
                            insert.setString(2, b.title);
                            insert.setString(3, b.author);
                            insert.setString(4, b.category);
                            insert.setInt(5, b.totalCopies);
                            insert.setInt(6, b.availableCopies);
                            insert.addBatch();
                        }
                        insert.executeBatch();
                    }
                    conn.commit();
                    return true;
                } catch (SQLException ex) {
                    conn.rollback();
                    throw ex;
                }
            } catch (SQLException ex) {
                showError("Could not save books data to the database:\n" + ex.getMessage());
                return false;
            }
        }

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(BOOKS_FILE), StandardCharsets.UTF_8))) {
            for (Book b : books) {
                writer.write(b.bookId + "|" + b.title + "|" + b.author + "|" + b.category + "|" + b.totalCopies + "|" + b.availableCopies);
                writer.newLine();
            }
            return true;
        } catch (IOException ex) {
            showError("Could not save books data to book.txt:\n" + ex.getMessage());
            return false;
        }
    }

    private boolean saveTransactions() {
        if (databaseAvailable) {
            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement clear = conn.prepareStatement("DELETE FROM library_transactions")) {
                        clear.executeUpdate();
                    }
                    String sql = "INSERT INTO library_transactions (transactionId, userId, userName, bookId, bookTitle, type, status, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                    try (PreparedStatement insert = conn.prepareStatement(sql)) {
                        for (Transaction t : transactions) {
                            insert.setString(1, t.transactionId);
                            insert.setString(2, t.userId);
                            insert.setString(3, t.userName);
                            insert.setString(4, t.bookId);
                            insert.setString(5, t.bookTitle);
                            insert.setString(6, t.type);
                            insert.setString(7, t.status);
                            insert.setString(8, t.timestamp);
                            insert.addBatch();
                        }
                        insert.executeBatch();
                    }
                    conn.commit();
                    return true;
                } catch (SQLException ex) {
                    conn.rollback();
                    throw ex;
                }
            } catch (SQLException ex) {
                showError("Could not save transaction data to the database:\n" + ex.getMessage());
                return false;
            }
        }

        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(TRANSACTIONS_FILE))) {
            out.writeObject(transactions);
            return true;
        } catch (IOException ex) {
            showError("Could not save transaction data:\n" + ex.getMessage());
            return false;
        }
    }

    private void seedDefaultAccounts() {
        boolean changed = false;
        for (String[] seed : DEFAULT_ACCOUNTS) {
            Account existing = null;
            for (Account account : accounts) {
                if (account.id.equalsIgnoreCase(seed[1])) {
                    existing = account;
                    break;
                }
            }
            if (existing == null) {
                addDemoAccount(seed[0], seed[1], seed[2], seed[3], seed[4]);
                changed = true;
                continue;
            }
            if (!isStoredHashValid(existing.passwordHash)) {
                existing.salt = UUID.randomUUID().toString();
                existing.passwordHash = hashPassword(seed[4], existing.salt);
                existing.status = "APPROVED";
                changed = true;
                System.out.println("Repaired credentials for required account: " + existing.id);
            }
            if (!existing.role.equals(seed[3])) {
                existing.role = seed[3];
                changed = true;
            }
        }
        if (changed) {
            saveAccounts();
        }
    }

    private static boolean isStoredHashValid(String storedHash) {
        if (storedHash == null || storedHash.isEmpty()) {
            return false;
        }
        try {
            return Base64.getDecoder().decode(storedHash).length == 32;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private void seedDemoData() {
        seedDefaultAccounts();

        if (books.isEmpty()) {
            books.add(new Book("B001", "Introduction to Java Programming", "John Smith", "Technology", 5));
            books.add(new Book("B002", "Advanced Mathematics for High Schools", "Jane Doe", "Academics", 3));
            books.add(new Book("B003", "Philippine History and Culture", "Maria Santos", "History", 4));
            books.add(new Book("B004", "Modern Physics Concepts", "Albert Einstein Jr.", "Science", 2));
            books.add(new Book("B005", "English Literature Classics", "William Shakespeare", "Literature", 6));
        addBook("To Kill a Mockingbird", "Harper Lee", "Fiction", "BK001", 5);
        addBook("1984", "George Orwell", "Dystopian", "BK002", 5);
        addBook("The Great Gatsby", "F. Scott Fitzgerald", "Classic", "BK003", 5);
        addBook("Pride and Prejudice", "Jane Austen", "Romance", "BK004", 5);
        addBook("The Hobbit", "J.R.R. Tolkien", "Fantasy", "BK005", 5);
        addBook("Fahrenheit 451", "Ray Bradbury", "Science Fiction", "BK006", 5);
        addBook("Jane Eyre", "Charlotte Brontë", "Classic", "BK007", 5);
        addBook("Animal Farm", "George Orwell", "Satire", "BK008", 5);
        addBook("Lord of the Flies", "William Golding", "Allegorical Fiction", "BK009", 5);
        addBook("The Catcher in the Rye", "J.D. Salinger", "Coming-of-Age", "BK010", 5);
        addBook("The Alchemist", "Paulo Coelho", "Adventure", "BK011", 5);
        addBook("The Chronicles of Narnia", "C.S. Lewis", "Fantasy", "BK012", 5);
        addBook("Harry Potter and the Sorcerer's Stone", "J.K. Rowling", "Fantasy", "BK013", 5);
        addBook("The Lord of the Rings", "J.R.R. Tolkien", "Fantasy", "BK014", 5);
        addBook("Brave New World", "Aldous Huxley", "Dystopian", "BK015", 5);
        addBook("The Diary of a Young Girl", "Anne Frank", "Biography", "BK016", 5);
        addBook("The Little Prince", "Antoine de Saint-Exupéry", "Fable", "BK017", 5);
        addBook("The Hobbit", "J.R.R. Tolkien", "Fantasy", "BK018", 5);
        addBook("Crime and Punishment", "Fyodor Dostoevsky", "Philosophical Fiction", "BK019", 5);
        addBook("The Odyssey", "Homer", "Epic", "BK020", 5);
        addBook("Moby-Dick", "Herman Melville", "Adventure", "BK021", 5);
        addBook("War and Peace", "Leo Tolstoy", "Historical Fiction", "BK022", 5);
        addBook("The Brothers Karamazov", "Fyodor Dostoevsky", "Philosophical Fiction", "BK023", 5);
        addBook("Madame Bovary", "Gustave Flaubert", "Classic", "BK024", 5);
        addBook("The Divine Comedy", "Dante Alighieri", "Epic Poetry", "BK025", 5);
        addBook("Les Misérables", "Victor Hugo", "Historical Fiction", "BK026", 5);
        addBook("Wuthering Heights", "Emily Brontë", "Gothic Fiction", "BK027", 5);
        addBook("Great Expectations", "Charles Dickens", "Classic", "BK028", 5);
        addBook("A Tale of Two Cities", "Charles Dickens", "Historical Fiction", "BK029", 5);
        addBook("Frankenstein", "Mary Shelley", "Gothic Science Fiction", "BK030", 5);
        addBook("Dracula", "Bram Stoker", "Gothic Horror", "BK031", 5);
        addBook("The Picture of Dorian Gray", "Oscar Wilde", "Philosophical Fiction", "BK032", 5);
        addBook("The Iliad", "Homer", "Epic", "BK033", 5);
        addBook("Don Quixote", "Miguel de Cervantes", "Satire", "BK034", 5);
        addBook("The Count of Monte Cristo", "Alexandre Dumas", "Adventure", "BK035", 5);
        addBook("Anna Karenina", "Leo Tolstoy", "Realist Fiction", "BK036", 5);
        addBook("The Metamorphosis", "Franz Kafka", "Absurdist Fiction", "BK037", 5);
        addBook("The Stranger", "Albert Camus", "Philosophical Fiction", "BK038", 5);
        addBook("One Hundred Years of Solitude", "Gabriel García Márquez", "Magical Realism", "BK039", 5);
        addBook("The Handmaid's Tale", "Margaret Atwood", "Dystopian", "BK040", 5);
        addBook("The Kite Runner", "Khaled Hosseini", "Drama", "BK041", 5);
        addBook("Life of Pi", "Yann Martel", "Adventure", "BK042", 5);
        addBook("The Book Thief", "Markus Zusak", "Historical Fiction", "BK043", 5);
        addBook("The Giver", "Lois Lowry", "Dystopian", "BK044", 5);
        addBook("Catch-22", "Joseph Heller", "Satire", "BK045", 5);
        addBook("Slaughterhouse-Five", "Kurt Vonnegut", "Science Fiction", "BK046", 5);
        addBook("A Clockwork Orange", "Anthony Burgess", "Dystopian", "BK047", 5);
        addBook("Of Mice and Men", "John Steinbeck", "Tragedy", "BK048", 5);
        addBook("The Grapes of Wrath", "John Steinbeck", "Realist Fiction", "BK049", 5);
        addBook("East of Eden", "John Steinbeck", "Epic", "BK050", 5);
        addBook("Fahrenheit 451", "Ray Bradbury", "Dystopian", "BK051", 5);
        addBook("The Sun Also Rises", "Ernest Hemingway", "Modernist Novel", "BK052", 5);
        addBook("The Old Man and the Sea", "Ernest Hemingway", "Adventure", "BK053", 5);
        addBook("For Whom the Bell Tolls", "Ernest Hemingway", "War Novel", "BK054", 5);
        addBook("A Farewell to Arms", "Ernest Hemingway", "Romance", "BK055", 5);
        addBook("Beloved", "Toni Morrison", "Historical Fiction", "BK056", 5);
        addBook("The Color Purple", "Alice Walker", "Epistolary Novel", "BK057", 5);
        addBook("Invisible Man", "Ralph Ellison", "Bildungsroman", "BK058", 5);
        addBook("Native Son", "Richard Wright", "Protest Novel", "BK059", 5);
        addBook("The Great Gatsby", "F. Scott Fitzgerald", "Tragedy", "BK060", 5);
        addBook("Tender is the Night", "F. Scott Fitzgerald", "Tragedy", "BK061", 5);
        addBook("Atlas Shrugged", "Ayn Rand", "Philosophical Fiction", "BK062", 5);
        addBook("The Fountainhead", "Ayn Rand", "Philosophical Fiction", "BK063", 5);
        addBook("Sapiens: A Brief History of Humankind", "Yuval Noah Harari", "Non-Fiction", "BK064", 5);
        addBook("Educated", "Tara Westover", "Memoir", "BK065", 5);
        addBook("Becoming", "Michelle Obama", "Memoir", "BK066", 5);
        addBook("Thinking, Fast and Slow", "Daniel Kahneman", "Psychology", "BK067", 5);
        addBook("Atomic Habits", "James Clear", "Self-Help", "BK068", 5);
        addBook("The 7 Habits of Highly Effective People", "Stephen Covey", "Self-Help", "BK069", 5);
        addBook("Deep Work", "Cal Newport", "Productivity", "BK070", 5);
        addBook("Rich Dad Poor Dad", "Robert T. Kiyosaki", "Finance", "BK071", 5);
        addBook("The Psychology of Money", "Morgan Housel", "Finance", "BK072", 5);
        addBook("Good to Great", "Jim Collins", "Business", "BK073", 5);
        addBook("Man's Search for Meaning", "Viktor E. Frankl", "Psychology", "BK074", 5);
        addBook("The Power of Now", "Eckhart Tolle", "Spirituality", "BK075", 5);
        addBook("Thinking Fast and Slow", "Daniel Kahneman", "Science", "BK076", 5);
        addBook("Cosmos", "Carl Sagan", "Science", "BK077", 5);
        addBook("A Brief History of Time", "Stephen Hawking", "Science", "BK078", 5);
        addBook("The Selfish Gene", "Richard Dawkins", "Science", "BK079", 5);
        addBook("Clean Code", "Robert C. Martin", "Technology", "BK080", 5);
        addBook("The Pragmatic Programmer", "Andy Hunt and Dave Thomas", "Technology", "BK081", 5);
        addBook("Introduction to Algorithms", "Thomas H. Cormen", "Technology", "BK082", 5);
        addBook("Design Patterns", "Erich Gamma et al.", "Technology", "BK083", 5);
        addBook("Code: The Hidden Language", "Charles Petzold", "Technology", "BK084", 5);
        addBook("The Art of Computer Programming", "Donald Knuth", "Technology", "BK085", 5);
        addBook("Artificial Intelligence: A Modern Approach", "Stuart Russell and Peter Norvig", "Technology", "BK086", 5);
        addBook("Python Crash Course", "Eric Matthes", "Technology", "BK087", 5);
        addBook("Fluent Python", "Luciano Ramalho", "Technology", "BK088", 5);
        addBook("Effective Java", "Joshua Bloch", "Technology", "BK089", 5);
        addBook("Head First Java", "Kathy Sierra and Bert Bates", "Technology", "BK090", 5);
        addBook("The Lord of the Rings", "J.R.R. Tolkien", "Fantasy", "BK091", 5);
        addBook("Dune", "Frank Herbert", "Science Fiction", "BK092", 5);
        addBook("Foundation", "Isaac Asimov", "Science Fiction", "BK093", 5);
        addBook("Neuromancer", "William Gibson", "Cyberpunk", "BK094", 5);
        addBook("Snow Crash", "Neal Stephenson", "Cyberpunk", "BK095", 5);
        addBook("Enders Game", "Orson Scott Card", "Science Fiction", "BK096", 5);
        addBook("The Martian", "Andy Weir", "Science Fiction", "BK097", 5);
        addBook("Project Hail Mary", "Andy Weir", "Science Fiction", "BK098", 5);
        addBook("Ready Player One", "Ernest Cline", "Sci-Fi Adventure", "BK099", 5);
        addBook("Hyperion", "Dan Simmons", "Science Fiction", "BK100", 5);
        saveBooks();
        }
}    

    private void addBook(String title, String author, String category, String bookId, int totalCopies) {
        books.add(new Book(bookId, title, author, category, totalCopies));
    }

    private void addDemoAccount(String name, String id, String email, String role, String password) {
        Account account = new Account(name, id, email, role, password);
        account.status = "APPROVED";
        accounts.add(account);
    }

    // -------------------- SIMULATED EMAIL NOTIFICATION --------------------

    private void sendEmailNotification(String recipientEmail, String subject, String body) {
        if (recipientEmail == null || recipientEmail.trim().isEmpty()) {
            return;
        }
        System.out.println("==========================================");
        System.out.println("SIMULATED EMAIL NOTIFICATION DISPATCHED:");
        System.out.println("To: " + recipientEmail);
        System.out.println("Subject: " + subject);
        System.out.println("Body:\n" + body);
        System.out.println("==========================================");
    }

    // -------------------- UI HELPERS --------------------

    private JLabel label(String text, int size, Color color, boolean bold) {
        JLabel result = new JLabel(text);
        result.setFont(new Font("Segoe UI", bold ? Font.BOLD : Font.PLAIN, size));
        result.setForeground(color);
        return result;
    }

    private JButton button(String text, Color color) {
        JButton result = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(isEnabled() ? getBackground() : new Color(180, 186, 196));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        result.setFont(new Font("Segoe UI", Font.BOLD, 13));
        result.setForeground(Color.WHITE);
        result.setBackground(color);
        result.setFocusPainted(false);
        result.setContentAreaFilled(false);
        result.setBorderPainted(false);
        result.setOpaque(false);
        result.setBorder(new EmptyBorder(11, 18, 11, 18));
        result.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        result.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                result.setBackground(color.brighter());
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                result.setBackground(color);
            }
        });
        return result;
    }

    private JButton linkButton(String text) {
        JButton result = new JButton(text);
        result.setFont(new Font("Segoe UI", Font.BOLD, 11));
        result.setForeground(BLUE);
        result.setContentAreaFilled(false);
        result.setBorderPainted(false);
        result.setFocusPainted(false);
        result.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return result;
    }

    private String databaseStatusText() {
        if (!databaseAvailable) {
            return "Offline mode — SQLite driver not found, using local files.";
        }
        return "SQLite connected — " + connectedDatabaseFile().getName() + " • " + books.size() + " books";
    }

    private void styleInput(JComponent component) {
        component.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        component.setPreferredSize(new Dimension(320, 40));
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        component.setBorder(new CompoundBorder(
            new LineBorder(new Color(215, 221, 232)),
            new EmptyBorder(5, 9, 5, 9)
        ));
    }

    private JPanel header(String title, String subtitle) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(NAVY);
        panel.setBorder(new EmptyBorder(24, 32, 24, 32));
        panel.add(label(title, 25, Color.WHITE, true));
        panel.add(Box.createVerticalStrut(6));
        panel.add(label(subtitle, 13, new Color(215, 224, 240), false));
        return panel;
    }

    private JPanel pageCard(int width) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Color.WHITE);
        card.setBorder(new CompoundBorder(
            new LineBorder(new Color(225, 230, 239)),
            new EmptyBorder(25, 30, 25, 30)
        ));
        card.setPreferredSize(new Dimension(width, 500));
        return card;
    }

    private void addField(JPanel panel, String title, JComponent input) {
        JLabel fieldLabel = label(title, 12, TEXT, true);
        fieldLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        input.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(fieldLabel);
        panel.add(Box.createVerticalStrut(5));
        panel.add(input);
        panel.add(Box.createVerticalStrut(13));
    }

    private void showMessage(String message) {
        JOptionPane.showMessageDialog(this, message, "DEIHS Library", JOptionPane.INFORMATION_MESSAGE);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "DEIHS Library", JOptionPane.WARNING_MESSAGE);
    }

    // -------------------- LOGIN PAGE --------------------

    private JPanel buildLoginPage() {
        JPanel page = new JPanel(new BorderLayout());
        page.setBackground(BG);
        page.add(header("DEIHS LIBRARY", "Library Management System • Secure Account Access"), BorderLayout.NORTH);

        JPanel center = new JPanel(new GridBagLayout());
        center.setBackground(BG);

        JPanel card = pageCard(420);
        card.setPreferredSize(new Dimension(420, 545));

        card.add(label("LOGIN TO YOUR PORTAL", 21, NAVY, true));
        card.add(Box.createVerticalStrut(22));

        loginRole = new JComboBox<String>(new String[]{"Student", "Teacher", "Principal", "Staff", "Admin"});
        loginId = new JTextField();
        loginPassword = new JPasswordField();

        styleInput(loginRole);
        styleInput(loginId);
        styleInput(loginPassword);

        addField(card, "Select Portal", loginRole);
        addField(card, "LRN / Institutional ID", loginId);
        addField(card, "Password", loginPassword);

        JButton loginButton = button("LOGIN", BLUE);
        JButton registerButton = button("CREATE ACCOUNT", GREEN);
        loginButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        registerButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        loginButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        registerButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));

        card.add(Box.createVerticalStrut(5));
        card.add(loginButton);
        card.add(Box.createVerticalStrut(10));
        card.add(registerButton);
        card.add(Box.createVerticalStrut(8));

        JPanel linkRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        linkRow.setOpaque(false);
        linkRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton forgotButton = linkButton("FORGOT PASSWORD?");
        JButton assistantButton = linkButton("     ASK THE ASSISTANT");
        linkRow.add(forgotButton);
        linkRow.add(assistantButton);
        card.add(linkRow);

        card.add(Box.createVerticalStrut(8));
        card.add(label("New accounts require administrator approval.", 11, Color.GRAY, false));
        card.add(Box.createVerticalStrut(6));

        JLabel dbStatus = label(databaseStatusText(), 10, databaseAvailable ? GREEN : RED, false);
        dbStatus.setToolTipText(connectedDatabaseFile().getAbsolutePath());
        card.add(dbStatus);

        loginButton.addActionListener(e -> login());
        forgotButton.addActionListener(e -> showPasswordResetDialog());
        assistantButton.addActionListener(e -> showAssistantDialog());
        loginPassword.addActionListener(e -> login());
        registerButton.addActionListener(e -> {
            clearRegistrationForm();
            cardLayout.show(root, "REGISTER");
        });

        center.add(card);
        page.add(center, BorderLayout.CENTER);
        return page;
    }

    // -------------------- REGISTRATION PAGE --------------------

    private JPanel buildRegistrationPage() {
        JPanel page = new JPanel(new BorderLayout());
        page.setBackground(BG);
        page.add(header("ACCOUNT REGISTRATION", "Register as a DEIHS student, teacher, principal, or staff member"), BorderLayout.NORTH);

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBackground(Color.WHITE);
        form.setBorder(new CompoundBorder(
            new LineBorder(new Color(225, 230, 239)),
            new EmptyBorder(22, 30, 22, 30)
        ));

        registerRole = new JComboBox<String>(USER_ROLES);
        registerName = new JTextField();
        registerId = new JTextField();
        registerEmail = new JTextField();
        registerPassword = new JPasswordField();
        registerConfirm = new JPasswordField();

        styleInput(registerRole);
        styleInput(registerName);
        styleInput(registerId);
        styleInput(registerEmail);
        styleInput(registerPassword);
        styleInput(registerConfirm);

        form.add(label("CREATE YOUR ACCOUNT", 20, NAVY, true));
        form.add(Box.createVerticalStrut(15));
        addField(form, "Portal / Account Type", registerRole);
        addField(form, "Complete Name", registerName);
        addField(form, "Student LRN (12 digits) or Employee ID", registerId);
        addField(form, "Email Address (for notifications)", registerEmail);
        addField(form, "Password (at least 8 characters)", registerPassword);
        addField(form, "Confirm Password", registerConfirm);

        JPanel actions = new JPanel(new GridLayout(1, 2, 10, 0));
        actions.setOpaque(false);
        JButton submit = button("SUBMIT REGISTRATION", GREEN);
        JButton back = button("BACK TO LOGIN", NAVY);
        actions.add(submit);
        actions.add(back);
        actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 45));
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);

        form.add(Box.createVerticalStrut(4));
        form.add(actions);
        form.add(Box.createVerticalStrut(12));
        form.add(label("Your request will be marked PENDING until an administrator reviews it.", 11, Color.GRAY, false));

        submit.addActionListener(e -> registerAccount());
        back.addActionListener(e -> cardLayout.show(root, "LOGIN"));

        JPanel wrapper = new JPanel(new GridBagLayout());
        wrapper.setBackground(BG);
        wrapper.add(form);

        JScrollPane scroll = new JScrollPane(wrapper);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        page.add(scroll, BorderLayout.CENTER);
        return page;
    }

    private void clearRegistrationForm() {
        registerRole.setSelectedIndex(0);
        registerName.setText("");
        registerId.setText("");
        registerEmail.setText("");
        registerPassword.setText("");
        registerConfirm.setText("");
    }

    private void registerAccount() {
        String name = registerName.getText().trim();
        String id = registerId.getText().trim();
        String email = registerEmail.getText().trim();
        String role = (String) registerRole.getSelectedItem();
        String password = new String(registerPassword.getPassword());
        String confirm = new String(registerConfirm.getPassword());

        if (name.isEmpty() || id.isEmpty() || password.isEmpty() || confirm.isEmpty()) {
            showError("Please complete your name, ID, and password fields.");
            return;
        }

        if (email.isEmpty() || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            showError("Please enter a valid email address for notifications.");
            return;
        }

        if ("Student".equals(role) && !id.matches("\\d{12}")) {
            showError("A student LRN must contain exactly 12 digits.");
            return;
        }

        if (password.length() < 8) {
            showError("Password must contain at least 8 characters.");
            return;
        }

        if (!password.equals(confirm)) {
            showError("The passwords do not match.");
            return;
        }

        for (Account account : accounts) {
            if (account.id.equalsIgnoreCase(id)) {
                showError("This ID is already registered. Please contact the administrator.");
                return;
            }
        }

        Account newAccount = new Account(name, id, email, role, password);
        newAccount.status = "PENDING";
        accounts.add(newAccount);

        if (!saveAccounts()) {
            accounts.remove(newAccount);
            return;
        }

        sendEmailNotification(email, "DEIHS Library - Account Registration Received",
            "Hello " + name + ",\n\nYour account registration for the DEIHS Library has been received and is currently PENDING review by the administrator.");

        showMessage("Registration request submitted successfully!\n\nAn email notification has been dispatched to: " + email);
        clearRegistrationForm();
        cardLayout.show(root, "LOGIN");
    }

    // -------------------- LOGIN LOGIC --------------------

    private void login() {
        String id = loginId.getText().trim();
        String password = new String(loginPassword.getPassword());
        String role = (String) loginRole.getSelectedItem();

        if (id.isEmpty() || password.isEmpty()) {
            showError("Enter your ID and password.");
            return;
        }

        Account found = null;
        for (Account account : accounts) {
            if (account.id.equalsIgnoreCase(id)) {
                found = account;
                break;
            }
        }

        if (found == null) {
            showError("No account with ID \"" + id + "\" is saved in the database.\n\n" +
                "If you just registered, the registration did not complete. Please register again.");
            return;
        }

        if (!found.role.equalsIgnoreCase(role)) {
            showError("This ID is registered under the \"" + found.role + "\" portal, not \"" + role + "\".\n\n" +
                "Select \"" + found.role + "\" in the Select Portal dropdown and try again.");
            return;
        }

        if (!passwordMatches(found, password)) {
            showError("Incorrect password for ID \"" + id + "\".\n\nUse FORGOT PASSWORD? on the login screen if you need to reset it.");
            return;
        }

        if (!"APPROVED".equals(found.status)) {
            showError("Your account status is " + found.status + ".\n\n" +
                "An administrator must approve it before you can log in:\n" +
                "1. Log in as ADMIN\n" +
                "2. Open MANAGE ACCOUNTS\n" +
                "3. Select your account and click APPROVE\n" +
                "4. Log in again with your own ID and password.");
            return;
        }

        currentAccount = found;
        loginPassword.setText("");
        showDashboard();
    }

    // -------------------- DASHBOARD --------------------

    private void showDashboard() {
        dashboardPanel = new JPanel(new BorderLayout());
        dashboardPanel.setBackground(BG);

        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(NAVY);
        top.setBorder(new EmptyBorder(18, 25, 18, 25));
        top.add(label("DEIHS LIBRARY MANAGEMENT SYSTEM", 21, Color.WHITE, true), BorderLayout.WEST);

        JButton logout = button("LOG OUT", RED);
        top.add(logout, BorderLayout.EAST);
        logout.addActionListener(e -> logout());

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(BG);
        content.setBorder(new EmptyBorder(30, 35, 30, 35));

        content.add(label("Welcome, " + currentAccount.name, 25, NAVY, true));
        content.add(Box.createVerticalStrut(6));
        content.add(label(currentAccount.role.toUpperCase() + " PORTAL", 14, BLUE, true));
        content.add(Box.createVerticalStrut(20));

        JPanel profile = new JPanel();
        profile.setLayout(new BoxLayout(profile, BoxLayout.Y_AXIS));
        profile.setBackground(Color.WHITE);
        profile.setBorder(new CompoundBorder(
            new LineBorder(new Color(225, 230, 239)),
            new EmptyBorder(18, 22, 18, 22)
        ));
        profile.setAlignmentX(Component.LEFT_ALIGNMENT);
        profile.add(label("ACCOUNT INFORMATION", 16, NAVY, true));
        profile.add(Box.createVerticalStrut(10));
        profile.add(label("Name: " + currentAccount.name, 13, TEXT, false));
        profile.add(Box.createVerticalStrut(6));
        profile.add(label("LRN / ID: " + currentAccount.id, 13, TEXT, false));
        profile.add(Box.createVerticalStrut(6));
        profile.add(label("Email for notifications: " + (currentAccount.email.isEmpty() ? "Not provided" : currentAccount.email), 13, TEXT, false));
        profile.add(Box.createVerticalStrut(6));
        profile.add(label("Account status: " + currentAccount.status, 13, GREEN, true));

        content.add(profile);
        content.add(Box.createVerticalStrut(25));

        if ("Admin".equals(currentAccount.role)) {
            buildAdminDashboard(content);
        } else {
            buildUserDashboard(content);
        }

        dashboardPanel.add(top, BorderLayout.NORTH);
        dashboardPanel.add(new JScrollPane(content), BorderLayout.CENTER);

        setContentPane(dashboardPanel);
        revalidate();
        repaint();
    }

    private void buildUserDashboard(JPanel content) {
        content.add(label("LIBRARY ACTIONS & SERVICES", 17, NAVY, true));
        content.add(Box.createVerticalStrut(12));

        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        actionPanel.setOpaque(false);
        actionPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton viewCatalogBtn = button("BROWSE & BORROW BOOKS", BLUE);
        JButton myTransactionsBtn = button("REFRESH TRANSACTIONS", NAVY);
        JButton requestBookBtn = button("REQUEST NEW BOOK", GREEN);
        JButton assistantBtn = button("ASK ASSISTANT", new Color(120, 80, 200));

        actionPanel.add(viewCatalogBtn);
        actionPanel.add(myTransactionsBtn);
        actionPanel.add(requestBookBtn);
        actionPanel.add(assistantBtn);

        content.add(actionPanel);
        content.add(Box.createVerticalStrut(25));
        content.add(label("Your Active Borrowed Books & Requests Summary", 14, TEXT, true));
        content.add(Box.createVerticalStrut(10));

        String[] columns = {"Transaction ID", "Book Title", "Type", "Status", "Timestamp"};
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            public boolean isCellEditable(int r, int c) { return false; }
        };

        for (Transaction t : transactions) {
            if (t.userId.equalsIgnoreCase(currentAccount.id)) {
                model.addRow(new Object[]{t.transactionId, t.bookTitle, t.type, t.status, t.timestamp});
            }
        }

        JTable table = new JTable(model);
        table.setRowHeight(26);
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        tableScroll.setPreferredSize(new Dimension(800, 200));
        content.add(tableScroll);

        viewCatalogBtn.addActionListener(e -> showBookCatalogDialog());
        myTransactionsBtn.addActionListener(e -> showDashboard());
        requestBookBtn.addActionListener(e -> showRequestBookDialog());
        assistantBtn.addActionListener(e -> showAssistantDialog());
    }

    private void buildAdminDashboard(JPanel content) {
        content.add(label("ADMINISTRATOR CONTROL PANEL", 17, NAVY, true));
        content.add(Box.createVerticalStrut(12));

        int pendingAccs = 0;
        int approvedAccs = 0;
        int pendingTxns = 0;
        for (Account a : accounts) {
            if (!"Admin".equals(a.role)) {
                if ("PENDING".equals(a.status)) pendingAccs++;
                else if ("APPROVED".equals(a.status)) approvedAccs++;
            }
        }
        for (Transaction t : transactions) if ("PENDING".equals(t.status)) pendingTxns++;

        content.add(label("Total Catalog Books Loaded: " + books.size(), 13, TEXT, true));
        content.add(Box.createVerticalStrut(5));
        content.add(label("Registered Accounts: " + approvedAccs + " approved • " + pendingAccs + " pending", 13, TEXT, true));
        content.add(Box.createVerticalStrut(5));
        content.add(label(databaseSummary(), 12, GREEN, true));
        content.add(Box.createVerticalStrut(5));
        content.add(label("Pending Account Requests: " + pendingAccs, 13, TEXT, true));
        content.add(Box.createVerticalStrut(5));
        content.add(label("Pending Borrow/Return/Book Requests: " + pendingTxns, 13, TEXT, true));
        content.add(Box.createVerticalStrut(15));

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        btnPanel.setOpaque(false);
        btnPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton manageAccs = button("MANAGE ACCOUNTS", BLUE);
        JButton manageTxns = button("MANAGE LIBRARY TRANSACTIONS", GREEN);
        JButton manageBooks = button("MANAGE BOOK CATALOG", NAVY);
        JButton databaseBtn = button("DATABASE", new Color(120, 80, 200));
        JButton assistantBtn = button("ASK ASSISTANT", new Color(60, 140, 160));

        btnPanel.add(manageAccs);
        btnPanel.add(manageTxns);
        btnPanel.add(manageBooks);
        btnPanel.add(databaseBtn);
        btnPanel.add(assistantBtn);

        content.add(btnPanel);

        manageAccs.addActionListener(e -> showApprovalPanel());
        manageTxns.addActionListener(e -> showManageTransactionsPanel());
        manageBooks.addActionListener(e -> showManageBooksPanel());
        databaseBtn.addActionListener(e -> showDatabaseDialog());
        assistantBtn.addActionListener(e -> showAssistantDialog());
    }

    // -------------------- BOOK CATALOG & BORROWING MODULE --------------------

    private void showBookCatalogDialog() {
        JDialog dialog = new JDialog(this, "DEIHS Library Catalog - Borrow & Return", true);
        dialog.setSize(850, 500);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout(10, 10));

        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(new EmptyBorder(12, 15, 12, 15));
        top.setBackground(NAVY);
        top.add(label("AVAILABLE BOOK CATALOG (" + books.size() + " TITLES)", 18, Color.WHITE, true), BorderLayout.WEST);
        dialog.add(top, BorderLayout.NORTH);

        String[] columns = {"Book ID", "Title", "Author", "Category", "Available / Total"};
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            public boolean isCellEditable(int r, int c) { return false; }
        };

        for (Book b : books) {
            model.addRow(new Object[]{b.bookId, b.title, b.author, b.category, b.availableCopies + " / " + b.totalCopies});
        }

        JTable table = new JTable(model);
        table.setRowHeight(28);
        dialog.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton borrowBtn = button("BORROW SELECTED BOOK", GREEN);
        JButton returnBtn = button("RETURN BOOK", BLUE);
        JButton closeBtn = button("CLOSE", RED);

        actions.add(borrowBtn);
        actions.add(returnBtn);
        actions.add(closeBtn);
        dialog.add(actions, BorderLayout.SOUTH);

        borrowBtn.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) {
                showError("Please select a book from the table to borrow.");
                return;
            }
            String bookId = (String) table.getValueAt(row, 0);
            Book selectedBook = null;
            for (Book b : books) {
                if (b.bookId.equals(bookId)) {
                    selectedBook = b;
                    break;
                }
            }

            if (selectedBook != null) {
                if (selectedBook.availableCopies <= 0) {
                    showError("Sorry, no physical copies are currently available for borrowing.");
                    return;
                }
                if (outstandingBorrows(currentAccount.id, selectedBook.bookId) > 0) {
                    showError("You already have this book borrowed. Return it before borrowing another copy.");
                    return;
                }

                Transaction txn = new Transaction(currentAccount.id, currentAccount.name, selectedBook.bookId, selectedBook.title, "BORROW");
                txn.status = "APPROVED";
                transactions.add(txn);

                selectedBook.availableCopies--;
                saveBooks();
                saveTransactions();

                sendEmailNotification(currentAccount.email, "DEIHS Library - Book Borrowed Successfully",
                    "Hello " + currentAccount.name + ",\n\nYou have successfully borrowed the book:\nTitle: " + selectedBook.title + "\nBook ID: " + selectedBook.bookId + "\nPlease return it on time.");

                showMessage("Book borrowed successfully! An email receipt has been sent to " + currentAccount.email);
                dialog.dispose();
                showDashboard();
            }
        });

        returnBtn.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) {
                showError("Please select the book you wish to return.");
                return;
            }
            String bookId = (String) table.getValueAt(row, 0);
            Book selectedBook = null;
            for (Book b : books) {
                if (b.bookId.equals(bookId)) {
                    selectedBook = b;
                    break;
                }
            }

            if (selectedBook != null) {
                if (outstandingBorrows(currentAccount.id, selectedBook.bookId) <= 0) {
                    showError("You have no active borrow of this title to return.");
                    return;
                }
                Transaction txn = new Transaction(currentAccount.id, currentAccount.name, selectedBook.bookId, selectedBook.title, "RETURN");
                txn.status = "APPROVED";
                transactions.add(txn);

                if (selectedBook.availableCopies < selectedBook.totalCopies) {
                    selectedBook.availableCopies++;
                }
                saveBooks();
                saveTransactions();

                sendEmailNotification(currentAccount.email, "DEIHS Library - Book Return Confirmed",
                    "Hello " + currentAccount.name + ",\n\nWe have received your returned book:\nTitle: " + selectedBook.title + "\nThank you!");

                showMessage("Book returned successfully! An email confirmation has been sent.");
                dialog.dispose();
                showDashboard();
            }
        });

        closeBtn.addActionListener(e -> dialog.dispose());
        dialog.setVisible(true);
    }

    // -------------------- REQUEST NEW BOOK MODULE --------------------

    private void showRequestBookDialog() {
        JDialog dialog = new JDialog(this, "Request New Book Addition", true);
        dialog.setSize(450, 380);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout());

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(new EmptyBorder(20, 20, 20, 20));
        form.setBackground(Color.WHITE);

        JTextField titleField = new JTextField();
        JTextField authorField = new JTextField();
        JTextField categoryField = new JTextField();

        styleInput(titleField);
        styleInput(authorField);
        styleInput(categoryField);

        form.add(label("REQUEST A NEW BOOK TO BE ADDED", 16, NAVY, true));
        form.add(Box.createVerticalStrut(15));
        addField(form, "Book Title", titleField);
        addField(form, "Author Name", authorField);
        addField(form, "Category / Subject", categoryField);

        JButton submitBtn = button("SUBMIT REQUEST", GREEN);
        submitBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        submitBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        form.add(submitBtn);

        submitBtn.addActionListener(e -> {
            String bTitle = titleField.getText().trim();
            String bAuthor = authorField.getText().trim();
            String bCategory = categoryField.getText().trim();

            if (bTitle.isEmpty() || bAuthor.isEmpty()) {
                showError("Please enter at least the book title and author.");
                return;
            }

            Transaction txn = new Transaction(currentAccount.id, currentAccount.name, "REQ-" + System.currentTimeMillis(), bTitle, "BOOK_REQUEST");
            transactions.add(txn);
            saveTransactions();

            sendEmailNotification(currentAccount.email, "DEIHS Library - Book Request Submitted",
                "Hello " + currentAccount.name + ",\n\nYour request to add '" + bTitle + "' by " + bAuthor + " to the DEIHS Library has been submitted to the administration.");

            showMessage("Book request submitted successfully! An email update has been dispatched.");
            dialog.dispose();
            showDashboard();
        });

        dialog.add(form, BorderLayout.CENTER);
        dialog.setVisible(true);
    }

    // -------------------- ADMIN PANELS --------------------

    private void showApprovalPanel() {
        JPanel page = new JPanel(new BorderLayout(10, 10));
        page.setBackground(BG);
        page.setBorder(new EmptyBorder(20, 22, 20, 22));

        page.add(label("ACCOUNT REGISTRATION REQUESTS", 21, NAVY, true), BorderLayout.NORTH);

        String[] columns = {"Name", "ID / LRN", "Portal", "Email", "Status"};
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            public boolean isCellEditable(int r, int c) { return false; }
        };

        for (Account account : accounts) {
            if (!"Admin".equals(account.role)) {
                model.addRow(new Object[]{account.name, account.id, account.role, account.email, account.status});
            }
        }

        JTable table = new JTable(model);
        table.setRowHeight(28);
        page.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.setBackground(BG);
        JButton approve = button("APPROVE", GREEN);
        JButton reject = button("REJECT", RED);
        JButton resetPass = button("RESET PASSWORD", new Color(120, 80, 200));
        JButton back = button("BACK TO DASHBOARD", NAVY);

        actions.add(approve);
        actions.add(reject);
        actions.add(resetPass);
        actions.add(back);

        resetPass.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) {
                showError("Select an account first.");
                return;
            }
            String id = String.valueOf(table.getValueAt(row, 1));
            for (Account account : accounts) {
                if (account.id.equals(id)) {
                    resetPasswordFor(account);
                    return;
                }
            }
        });
        page.add(actions, BorderLayout.SOUTH);

        approve.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) { showError("Select an account first."); return; }
            String id = String.valueOf(table.getValueAt(row, 1));
            for (Account a : accounts) {
                if (a.id.equals(id)) {
                    a.status = "APPROVED";
                    saveAccounts();
                    sendEmailNotification(a.email, "DEIHS Library - Account Approved", "Hello " + a.name + ",\n\nYour account has been APPROVED! You can now log in.");
                    showMessage("Account approved and email notification sent.");
                    showApprovalPanel();
                    return;
                }
            }
        });

        reject.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) { showError("Select an account first."); return; }
            String id = String.valueOf(table.getValueAt(row, 1));
            for (Account a : accounts) {
                if (a.id.equals(id)) {
                    a.status = "REJECTED";
                    saveAccounts();
                    sendEmailNotification(a.email, "DEIHS Library - Account Status Update", "Hello " + a.name + ",\n\nYour account request was rejected.");
                    showMessage("Account rejected.");
                    showApprovalPanel();
                    return;
                }
            }
        });

        back.addActionListener(e -> showDashboard());

        dashboardPanel.removeAll();
        dashboardPanel.add(page, BorderLayout.CENTER);
        dashboardPanel.revalidate();
        dashboardPanel.repaint();
    }

    private void showManageTransactionsPanel() {
        JPanel page = new JPanel(new BorderLayout(10, 10));
        page.setBackground(BG);
        page.setBorder(new EmptyBorder(20, 22, 20, 22));

        page.add(label("MANAGE BOOK REQUESTS & TRANSACTIONS", 21, NAVY, true), BorderLayout.NORTH);

        String[] columns = {"Txn ID", "User Name", "Book/Request Title", "Type", "Status"};
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            public boolean isCellEditable(int r, int c) { return false; }
        };

        for (Transaction t : transactions) {
            model.addRow(new Object[]{t.transactionId, t.userName, t.bookTitle, t.type, t.status});
        }

        JTable table = new JTable(model);
        table.setRowHeight(28);
        page.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.setBackground(BG);
        JButton approveReq = button("APPROVE REQUEST", GREEN);
        JButton back = button("BACK TO DASHBOARD", NAVY);

        actions.add(approveReq);
        actions.add(back);
        page.add(actions, BorderLayout.SOUTH);

        approveReq.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) { showError("Select a transaction first."); return; }
            String txnId = (String) table.getValueAt(row, 0);
            for (Transaction t : transactions) {
                if (t.transactionId.equals(txnId)) {
                    t.status = "APPROVED";
                    if ("BOOK_REQUEST".equals(t.type)) {
                        books.add(new Book(nextBookId(), t.bookTitle, "Requested by " + t.userName, "General", 3));
                        saveBooks();
                    }
                    saveTransactions();
                    showMessage("Transaction updated to APPROVED successfully!");
                    showManageTransactionsPanel();
                    return;
                }
            }
        });

        back.addActionListener(e -> showDashboard());

        dashboardPanel.removeAll();
        dashboardPanel.add(page, BorderLayout.CENTER);
        dashboardPanel.revalidate();
        dashboardPanel.repaint();
    }

    private void showManageBooksPanel() {
        JPanel page = new JPanel(new BorderLayout(10, 10));
        page.setBackground(BG);
        page.setBorder(new EmptyBorder(20, 22, 20, 22));

        page.add(label("MANAGE BOOK CATALOG (" + books.size() + " TITLES)", 21, NAVY, true), BorderLayout.NORTH);

        String[] columns = {"Book ID", "Title", "Author", "Category", "Available / Total"};
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            public boolean isCellEditable(int r, int c) { return false; }
        };

        for (Book b : books) {
            model.addRow(new Object[]{b.bookId, b.title, b.author, b.category, b.availableCopies + " / " + b.totalCopies});
        }

        JTable table = new JTable(model);
        table.setRowHeight(28);
        page.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel actions = new JPanel(new BorderLayout());
        actions.setBackground(BG);
        JButton addBookBtn = button("ADD NEW BOOK", GREEN);
        JButton back = button("BACK TO DASHBOARD", NAVY);

        JPanel addPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        addPanel.setOpaque(false);
        addPanel.add(addBookBtn);

        JPanel backPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        backPanel.setOpaque(false);
        backPanel.add(back);

        actions.add(addPanel, BorderLayout.WEST);
        actions.add(backPanel, BorderLayout.EAST);
        page.add(actions, BorderLayout.SOUTH);

        addBookBtn.addActionListener(e -> {
            String title = JOptionPane.showInputDialog(this, "Enter Book Title:");
            if (title == null || title.trim().isEmpty()) return;
            String author = JOptionPane.showInputDialog(this, "Enter Author Name:");
            if (author == null) author = "Unknown";
            String category = JOptionPane.showInputDialog(this, "Enter Category:");
            if (category == null) category = "General";

            books.add(new Book(nextBookId(), title, author, category, 5));
            saveBooks();
            showMessage("Book added to catalog and saved to the database!");
            showManageBooksPanel();
        });

        back.addActionListener(e -> showDashboard());

        dashboardPanel.removeAll();
        dashboardPanel.add(page, BorderLayout.CENTER);
        dashboardPanel.revalidate();
        dashboardPanel.repaint();
    }

    // -------------------- PASSWORD RESET SERVICE --------------------

    private void showPasswordResetDialog() {
        JDialog dialog = new JDialog(this, "Password Reset Service", true);
        dialog.setSize(460, 480);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout());

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBackground(Color.WHITE);
        form.setBorder(new EmptyBorder(22, 26, 22, 26));

        JTextField idField = new JTextField();
        JTextField emailField = new JTextField();
        JPasswordField newPassword = new JPasswordField();
        JPasswordField confirmPassword = new JPasswordField();
        styleInput(idField);
        styleInput(emailField);
        styleInput(newPassword);
        styleInput(confirmPassword);

        form.add(label("RESET YOUR PASSWORD", 17, NAVY, true));
        form.add(Box.createVerticalStrut(8));
        form.add(label("Verify your ID and registered email, then choose a new password.", 11, Color.GRAY, false));
        form.add(Box.createVerticalStrut(14));
        addField(form, "LRN / Institutional ID", idField);
        addField(form, "Registered Email Address", emailField);
        addField(form, "New Password (8+ characters)", newPassword);
        addField(form, "Confirm New Password", confirmPassword);

        JPanel actions = new JPanel(new GridLayout(1, 2, 12, 0));
        actions.setOpaque(false);
        JButton submit = button("RESET PASSWORD", BLUE);
        JButton cancel = button("CANCEL", NAVY);
        actions.add(submit);
        actions.add(cancel);
        actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(actions);

        submit.addActionListener(e -> {
            String id = idField.getText().trim();
            String email = emailField.getText().trim();
            String first = new String(newPassword.getPassword());
            String second = new String(confirmPassword.getPassword());

            if (id.isEmpty() || email.isEmpty() || first.isEmpty() || second.isEmpty()) {
                showError("Please complete every field.");
                return;
            }
            Account target = null;
            for (Account account : accounts) {
                if (account.id.equalsIgnoreCase(id)) {
                    target = account;
                    break;
                }
            }
            if (target == null || target.email == null || !target.email.equalsIgnoreCase(email)) {
                showError("No account matches that ID and email address.");
                return;
            }
            if (first.length() < 8) {
                showError("Password must contain at least 8 characters.");
                return;
            }
            if (!first.equals(second)) {
                showError("The passwords do not match.");
                return;
            }
            target.salt = UUID.randomUUID().toString();
            target.passwordHash = hashPassword(first, target.salt);
            if (saveAccounts()) {
                sendEmailNotification(target.email, "DEIHS Library - Password Reset",
                    "Hello " + target.name + ",\n\nYour password has been reset successfully.");
                showMessage("Password updated. You can now log in with your new password.");
                dialog.dispose();
            }
        });
        cancel.addActionListener(e -> dialog.dispose());

        dialog.add(form, BorderLayout.CENTER);
        dialog.setVisible(true);
    }

    private void resetPasswordFor(Account account) {
        JPasswordField field = new JPasswordField();
        int result = JOptionPane.showConfirmDialog(this, field,
            "Enter a new password for " + account.name + " (" + account.id + "):",
            JOptionPane.OK_CANCEL_OPTION);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        String password = new String(field.getPassword());
        if (password.length() < 8) {
            showError("Password must contain at least 8 characters.");
            return;
        }
        account.salt = UUID.randomUUID().toString();
        account.passwordHash = hashPassword(password, account.salt);
        if (saveAccounts()) {
            sendEmailNotification(account.email, "DEIHS Library - Password Reset",
                "Hello " + account.name + ",\n\nYour password has been reset by the administrator.");
            showMessage("Password reset for " + account.name + " (" + account.id + ").");
        }
    }

    // -------------------- LIBRARY ASSISTANT --------------------

    private void showAssistantDialog() {
        JDialog dialog = new JDialog(this, "DEIHS Library Assistant", true);
        dialog.setSize(540, 500);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout());

        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(NAVY);
        top.setBorder(new EmptyBorder(12, 16, 12, 16));
        top.add(label("DEIHS LIBRARY ASSISTANT", 16, Color.WHITE, true), BorderLayout.WEST);
        dialog.add(top, BorderLayout.NORTH);

        JTextArea transcript = new JTextArea();
        transcript.setEditable(false);
        transcript.setLineWrap(true);
        transcript.setWrapStyleWord(true);
        transcript.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        transcript.setBackground(Color.WHITE);
        transcript.setBorder(new EmptyBorder(12, 14, 12, 14));
        transcript.setText("Assistant: Hello! Ask me about borrowing, returning, due dates, accounts, "
            + "approvals, password resets or the database.\n\n");
        dialog.add(new JScrollPane(transcript), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.setBackground(BG);
        bottom.setBorder(new EmptyBorder(10, 12, 12, 12));
        JTextField input = new JTextField();
        styleInput(input);
        JButton send = button("SEND", BLUE);
        bottom.add(input, BorderLayout.CENTER);
        bottom.add(send, BorderLayout.EAST);
        dialog.add(bottom, BorderLayout.SOUTH);

        Runnable submit = () -> {
            String question = input.getText().trim();
            if (question.isEmpty()) {
                return;
            }
            transcript.append("You: " + question + "\n");
            transcript.append("Assistant: " + assistantReply(question) + "\n\n");
            input.setText("");
            transcript.setCaretPosition(transcript.getDocument().getLength());
        };
        send.addActionListener(e -> submit.run());
        input.addActionListener(e -> submit.run());

        dialog.setVisible(true);
    }

    private String assistantReply(String question) {
        String q = question.toLowerCase();
        if (containsAny(q, "borrow", "loan", "check out", "checkout")) {
            return "To borrow a book: log in, open BROWSE & BORROW BOOKS, select a title and press BORROW SELECTED BOOK. Titles showing 0 available copies cannot be borrowed.";
        }
        if (containsAny(q, "return", "give back")) {
            return "To return: open BROWSE & BORROW BOOKS, select the title you borrowed and press RETURN BOOK. The copy becomes available again immediately.";
        }
        if (containsAny(q, "due", "deadline", "late", "fine")) {
            return "Please return borrowed books within 7 days. Late returns stay visible in the admin transaction log for the librarian.";
        }
        if (containsAny(q, "password", "forgot", "reset")) {
            return "If you forgot your password, click FORGOT PASSWORD? on the login screen, enter your ID and registered email, then set a new password. An administrator can also reset it from MANAGE ACCOUNTS.";
        }
        if (containsAny(q, "approve", "approval", "pending", "cannot log", "can't log", "can't login", "cannot login")) {
            return "New accounts start as PENDING. An administrator must open MANAGE ACCOUNTS, select the account and press APPROVE before that user can log in.";
        }
        if (containsAny(q, "register", "sign up", "create account")) {
            return "Click CREATE ACCOUNT on the login screen. Students need a 12-digit LRN, a valid email and a password of at least 8 characters.";
        }
        if (containsAny(q, "database", "sqlite", "backup", "db", "integrity")) {
            return "All records live in deihs_library.db. Administrators can open DATABASE on the dashboard to check integrity and create a backup.";
        }
        if (containsAny(q, "book", "catalog", "author", "subject", "request")) {
            return "Browse every title from BROWSE & BORROW BOOKS. To suggest a new title, use REQUEST NEW BOOK on the dashboard.";
        }
        if (containsAny(q, "admin", "administrator", "librarian", "manage")) {
            return "Administrators can approve accounts, reset passwords, manage transactions, add books and check the database from the admin dashboard.";
        }
        if (q.matches(".*\\b(hi|hello|hey)\\b.*")) {
            return "Hello! I can help with borrowing, returns, due dates, registration, approvals, password resets and the database.";
        }
        if (containsAny(q, "thank")) {
            return "You're welcome! Anything else I can help with?";
        }
        return "I'm not sure about that yet. Try asking about borrowing, returning, due dates, registration, approvals, password resets or the database. For other concerns, please contact the DEIHS librarian.";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    // -------------------- DATABASE INFORMATION --------------------

    private String databaseSummary() {
        if (!databaseAvailable) {
            return "Storage: offline local files";
        }
        File dbFile = connectedDatabaseFile();
        long kb = Math.max(1, dbFile.length() / 1024);
        return "Database: " + dbFile.getName() + " (" + kb + " KB) • " + accounts.size()
            + " account(s), " + books.size() + " book(s), " + transactions.size() + " transaction(s)";
    }

    private void showDatabaseDialog() {
        JDialog dialog = new JDialog(this, "Database", true);
        dialog.setSize(620, 380);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout());

        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(NAVY);
        top.setBorder(new EmptyBorder(12, 16, 12, 16));
        top.add(label("DATABASE STATUS", 16, Color.WHITE, true), BorderLayout.WEST);
        dialog.add(top, BorderLayout.NORTH);

        JTextArea report = new JTextArea();
        report.setEditable(false);
        report.setFont(new Font("Consolas", Font.PLAIN, 13));
        report.setBorder(new EmptyBorder(14, 16, 14, 16));
        report.setBackground(Color.WHITE);
        dialog.add(new JScrollPane(report), BorderLayout.CENTER);

        Runnable refresh = () -> report.setText(buildDatabaseReport());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton check = button("CHECK INTEGRITY", BLUE);
        JButton backup = button("BACKUP NOW", GREEN);
        JButton close = button("CLOSE", NAVY);
        actions.add(check);
        actions.add(backup);
        actions.add(close);
        dialog.add(actions, BorderLayout.SOUTH);

        refresh.run();
        check.addActionListener(e -> refresh.run());
        backup.addActionListener(e -> {
            backupDatabaseNow();
            refresh.run();
        });
        close.addActionListener(e -> dialog.dispose());
        dialog.setVisible(true);
    }

    private String buildDatabaseReport() {
        if (!databaseAvailable) {
            return "SQLite is not available.\n\n"
                + "Data is stored in local files instead of the database.\n"
                + "Add lib/sqlite-jdbc-3.54.1.0.jar to the classpath and restart.";
        }
        File dbFile = connectedDatabaseFile();
        StringBuilder report = new StringBuilder();
        report.append("File:         ").append(dbFile.getAbsolutePath()).append('\n');
        report.append("Size:         ").append(Math.max(1, dbFile.length() / 1024)).append(" KB\n");
        report.append("Accounts:     ").append(accounts.size()).append('\n');
        report.append("Books:        ").append(books.size()).append('\n');
        report.append("Transactions: ").append(transactions.size()).append('\n');
        try (Connection conn = getConnection()) {
            report.append("Integrity:    ").append(integrityCheck(conn)).append('\n');
        } catch (SQLException ex) {
            report.append("Integrity:    unavailable (").append(ex.getMessage()).append(")\n");
        }
        report.append('\n');
        report.append("Schema:       repaired automatically at startup\n");
        report.append("Backups:      ").append(countBackups(dbFile.getParentFile(), dbFile.getName()))
            .append(" file(s) in the project folder\n");
        return report.toString();
    }

    private int countBackups(File folder, String name) {
        File[] files = folder == null ? null : folder.listFiles();
        if (files == null) {
            return 0;
        }
        int count = 0;
        for (File file : files) {
            if (file.getName().startsWith(name + ".backup-") || file.getName().startsWith(name + ".broken-")) {
                count++;
            }
        }
        return count;
    }

    private int outstandingBorrows(String userId, String bookId) {
        int balance = 0;
        for (Transaction t : transactions) {
            if (!t.userId.equalsIgnoreCase(userId) || !t.bookId.equals(bookId)) {
                continue;
            }
            if ("BORROW".equals(t.type) && "APPROVED".equals(t.status)) {
                balance++;
            } else if ("RETURN".equals(t.type)) {
                balance--;
            }
        }
        return balance;
    }

    private String nextBookId() {
        int highest = 0;
        for (Book book : books) {
            String digits = book.bookId.replaceAll("^\\D+", "");
            try {
                highest = Math.max(highest, Integer.parseInt(digits));
            } catch (NumberFormatException ignored) {}
        }
        String id;
        do {
            highest++;
            id = String.format("BK%03d", highest);
        } while (bookIdExists(id));
        return id;
    }

    private boolean bookIdExists(String id) {
        for (Book book : books) {
            if (book.bookId.equalsIgnoreCase(id)) {
                return true;
            }
        }
        return false;
    }

    // -------------------- LOGOUT --------------------

    private void logout() {
        currentAccount = null;
        loginId.setText("");
        loginPassword.setText("");
        loginRole.setSelectedIndex(0);

        setContentPane(root);
        cardLayout.show(root, "LOGIN");
        revalidate();
        repaint();
    }

    // -------------------- MAIN --------------------

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    UIManager.setLookAndFeel(
                        UIManager.getSystemLookAndFeelClassName()
                    );
                } catch (Exception ignored) {}

                new DEIHSLibrary().setVisible(true);
            }
        });
    }
}