package com.example.signer.ui;

import com.example.signer.SignerApplication;
import com.example.signer.pdf.PdfDocumentInfo;
import com.example.signer.pdf.PdfDocumentService;
import com.example.signer.signing.PdfSigningService;
import com.example.signer.signing.SigningResult;
import com.example.signer.token.SigningAlgorithm;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.TokenInfo;
import com.example.signer.token.TokenService;
import com.example.signer.token.TokenState;
import com.example.signer.verification.PdfVerificationService;
import com.example.signer.verification.SignatureVerificationResult;
import com.example.signer.pdf.PdfRenderService;
import com.example.signer.ui.workspace.SigningWorkspace;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * JavaFX entry point driving the complete signing flow in a single window.
 *
 * <p>Spring is started from {@link #init()} (headless disabled, no web server)
 * so the UI can consume {@link TokenService} and friends directly. The flow:
 *
 * <ol>
 *   <li>pick a PDF (described locally with {@link PdfDocumentService}),</li>
 *   <li>detect the PKCS#11 token and choose a signing certificate,</li>
 *   <li>authenticate with the PIN (wiped immediately after use),</li>
 *   <li>sign the selected PDF into a temporary signed copy,</li>
 *   <li>verify the produced signature and save the signed PDF copy.
 * </ol>
 *
 * <p>All blocking calls run on background threads via {@link Task} and return
 * to the JavaFX thread through {@code setOnSucceeded}/{@code setOnFailed}.
 * The Spring context is closed in {@link #stop()}.
 *
 * <p>Signing embeds a CMS signature in a temporary PDF copy; "Save signed
 * PDF…" copies that file to a user-chosen location.
 */
public class SignerFxApplication extends Application {

    private static final Logger log = LoggerFactory.getLogger(SignerFxApplication.class);

    private ConfigurableApplicationContext context;

    private Stage stage;
    private BorderPane root;
    private Label appTitle;
    private Label appSubtitle;
    private boolean pdfLoadInProgress;

    // PDF -----------------------------------------------------------------
    private Label pdfInfo;
    private Button choosePdfButton;
    private Path selectedPdf;

    // Token -----------------------------------------------------------------
    private Label statePill;
    private Label tokenInfoLabel;
    private Button detectButton;
    private ComboBox<String> certCombo;
    private SigningCertificate selectedCert;
    private String selectedAlias;
    private Label certInfoLabel;

    // PIN / actions ---------------------------------------------------------
    private PasswordField pinField;
    private Button authenticateButton;
    private Button cancelButton;
    private Button signButton;
    private Button verifyButton;
    private Button saveButton;
    private Button logoutButton;

    private VBox pdfSection;
    private VBox tokenSection;
    private VBox certSection;
    private VBox pinSection;

    private ProgressBar progressBar;
    private Label statusLabel;
    private SigningResult lastSigningResult;
    private Path signedPdfPath;

    // step indicator labels
    private Label pdfStepBadge;
    private Label tokenStepBadge;
    private Label certStepBadge;
    private Label pinStepBadge;
    private Label statusDot;

    @Override
    public void init() {
        log.info("Initializing Spring application context");
        context = new SpringApplicationBuilder(SignerApplication.class)
                .headless(false)
                .web(WebApplicationType.NONE)
                .run(getParameters().getRaw().toArray(new String[0]));
        log.info("Spring application context initialized");
    }

    @Override
    public void start(Stage stage) {
        log.info("Starting JavaFX stage");
        this.stage = stage;

        // header -------------------------------------------------------------
        Label appLogo = new Label("\u2713");
        appLogo.getStyleClass().setAll("app-logo");
        appTitle = new Label("SignFlow");
        appTitle.getStyleClass().add("app-name");
        appSubtitle = new Label("PDF Digital Signing");
        appSubtitle.getStyleClass().add("app-tagline");
        HBox brand = new HBox(12, appLogo, new VBox(2, appTitle, appSubtitle));
        brand.setAlignment(Pos.CENTER_LEFT);
        HBox header = new HBox(brand);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(12, 20, 12, 20));
        header.getStyleClass().add("app-header");

        // content -------------------------------------------------------------
        VBox content = new VBox(14);
        content.setPadding(new Insets(14, 20, 14, 20));
        content.getStyleClass().add("content-pane");
        content.setMaxWidth(Double.MAX_VALUE);
        content.setAlignment(Pos.TOP_LEFT);

        // --- step 1: PDF -----------------------------------------------------
        pdfStepBadge = createStepBadge(1);
        Label pdfTitle = sectionTitle("PDF document");
        HBox pdfHeader = new HBox(8, pdfStepBadge, pdfTitle);
        pdfHeader.setAlignment(Pos.CENTER_LEFT);
        Label pdfHint = hintLabel("Choose the PDF you want to sign. The file is never modified — a signed copy is created.");
        pdfInfo = new Label("No PDF selected");
        pdfInfo.getStyleClass().add("section-hint");
        pdfInfo.setWrapText(true);
        pdfInfo.setMaxWidth(Double.MAX_VALUE);
        choosePdfButton = new Button("Open PDF");
        choosePdfButton.getStyleClass().add("btn-secondary");
        choosePdfButton.setOnAction(e -> {
            log.info("Choose PDF clicked");
            choosePdf();
        });
        HBox pdfRow = new HBox(10, choosePdfButton, pdfInfo);
        pdfRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(pdfInfo, Priority.ALWAYS);
        pdfSection = new VBox(5, pdfHeader, pdfHint, pdfRow);
        pdfSection.getStyleClass().add("card");

        // --- step 2: token -----------------------------------------------------
        tokenStepBadge = createStepBadge(2);
        Label tokenTitle = sectionTitle("Security device");
        HBox tokenHeader = new HBox(8, tokenStepBadge, tokenTitle);
        tokenHeader.setAlignment(Pos.CENTER_LEFT);
        Label tokenHint = hintLabel("Detect the connected cryptographic USB key/token.");
        statusDot = new Label("\u25CF");
        statusDot.getStyleClass().addAll("status-dot", "status-dot--inactive");
        statePill = new Label("NOT DETECTED");
        statePill.getStyleClass().addAll("status-pill");
        detectButton = new Button("Detect token");
        detectButton.getStyleClass().add("btn-secondary");
        detectButton.setOnAction(e -> detect());
        HBox tokenRow = new HBox(8, statusDot, statePill, detectButton);
        tokenRow.setAlignment(Pos.CENTER_LEFT);
        tokenInfoLabel = new Label("No token");
        tokenInfoLabel.getStyleClass().add("section-hint");
        tokenInfoLabel.setWrapText(true);
        tokenSection = new VBox(5, tokenHeader, tokenHint, tokenRow, tokenInfoLabel);
        tokenSection.getStyleClass().add("card");

        // --- step 3: certificate ---------------------------------------------------
        certStepBadge = createStepBadge(3);
        Label certTitle = sectionTitle("Certificate");
        HBox certHeader = new HBox(8, certStepBadge, certTitle);
        certHeader.setAlignment(Pos.CENTER_LEFT);
        Label certHint = hintLabel("Choose the signing certificate stored on the security device.");
        certCombo = new ComboBox<>();
        certCombo.setPromptText("Select a signing certificate");
        certCombo.setMaxWidth(Double.MAX_VALUE);
        certCombo.setOnAction(e -> onCertificateSelected());
        certInfoLabel = new Label(" ");
        certInfoLabel.getStyleClass().add("section-hint");
        certInfoLabel.setWrapText(true);
        certSection = new VBox(5, certHeader, certHint, certCombo, certInfoLabel);
        certSection.getStyleClass().add("card");

        // --- step 4: PIN -----------------------------------------------------------
        pinStepBadge = createStepBadge(4);
        Label pinTitle = sectionTitle("Unlock device");
        HBox pinHeader = new HBox(8, pinStepBadge, pinTitle);
        pinHeader.setAlignment(Pos.CENTER_LEFT);
        Label pinHint = hintLabel("Enter your security device PIN. The PIN is wiped immediately after use.");
        pinField = new PasswordField();
        pinField.setPromptText("Enter PIN");
        pinField.setTooltip(new Tooltip("Press Enter to authenticate"));
        pinField.setOnAction(e -> authenticate());
        authenticateButton = new Button("Unlock Device");
        authenticateButton.getStyleClass().add("btn-primary");
        authenticateButton.setTooltip(new Tooltip("Authenticate with the security device PIN (Enter)"));
        authenticateButton.setOnAction(e -> authenticate());
        cancelButton = new Button("Cancel");
        cancelButton.getStyleClass().add("btn-ghost");
        cancelButton.setTooltip(new Tooltip("Abort the PIN entry (Esc)"));
        cancelButton.setOnAction(e -> cancelAuthentication());
        HBox pinRow = new HBox(8, pinField, authenticateButton, cancelButton);
        pinRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(pinField, Priority.ALWAYS);
        pinSection = new VBox(5, pinTitle, pinHint, pinRow);
        pinSection.getStyleClass().add("card");

        // --- actions -------------------------------------------------------------
        signButton = new Button("Sign Document");
        signButton.getStyleClass().add("btn-primary");
        signButton.setOnAction(e -> {
            log.info("Sign Document clicked, selectedPdf={} selectedAlias={}", selectedPdf, selectedAlias);
            sign();
        });
        verifyButton = new Button("Verify Signature");
        verifyButton.getStyleClass().add("btn-secondary");
        verifyButton.setOnAction(e -> verify());
        saveButton = new Button("Download Signed PDF");
        saveButton.getStyleClass().add("btn-secondary");
        saveButton.setOnAction(e -> {
            log.info("Save signed PDF clicked, signedPdfPath={}", signedPdfPath);
            saveSignedPdf();
        });
        logoutButton = new Button("Logout");
        logoutButton.getStyleClass().add("btn-danger");
        logoutButton.setOnAction(e -> logout());
        Button workspaceButton = new Button("Signing workspace…");
        workspaceButton.getStyleClass().add("btn-secondary");
        workspaceButton.setTooltip(new Tooltip("Open the Adobe-like workspace: upload a PDF, draw a signature box, sign and download"));
        workspaceButton.setOnAction(e -> openWorkspace());
        FlowPane actionsFlow = new FlowPane(10, 10, signButton, verifyButton, saveButton, logoutButton, workspaceButton);
        actionsFlow.setAlignment(Pos.CENTER_LEFT);

        // --- progress / status -----------------------------------------------------
        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        statusLabel = new Label("Ready");
        statusLabel.getStyleClass().add("status-text");
        statusLabel.setWrapText(true);
        VBox bottom = new VBox(6, progressBar, statusLabel);
        bottom.getStyleClass().add("status-bar");

        content.getChildren().addAll(pdfSection, tokenSection, certSection, pinSection, actionsFlow);

        ScrollPane contentScroll = new ScrollPane(content);
        contentScroll.setFitToWidth(true);
        contentScroll.setFitToHeight(false);
        contentScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        contentScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        contentScroll.getStyleClass().add("content-scroll");

        root = new BorderPane();
        root.setTop(header);
        root.setCenter(contentScroll);
        root.setBottom(bottom);
        root.getStyleClass().add("content-pane");

        Scene scene = new Scene(root, 900, 700);
        scene.getStylesheets().add(
                getClass().getResource("/ui/application.css").toExternalForm());
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE
                    && context != null && context.isActive()
                    && context.getBean(TokenService.class).getState() == TokenState.WAITING_FOR_PIN) {
                cancelAuthentication();
                return;
            }
            if (!e.isControlDown()) {
                return;
            }
            switch (e.getCode()) {
                case O -> {
                    e.consume();
                    choosePdf();
                }
                case S -> {
                    e.consume();
                    saveSignedPdf();
                }
                case ENTER -> {
                    e.consume();
                    authenticate();
                }
                default -> {
                    // ignore other shortcuts
                }
            }
        });
        setupDropTarget(scene, root);
        stage.setTitle("PDF Digital Signer");
        stage.setScene(scene);
        stage.setMinWidth(760);
        stage.setMinHeight(560);
        stage.show();

        // When the window regains focus, re-sync token UI state so a token that was
        // unlocked/removed in another window (e.g. the workspace) is reflected here.
        stage.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused && context != null && context.isActive()) {
                refreshTokenUi();
                refreshPdfButtons();
            }
        });

        refreshTokenStateInBackground();
        refreshPdfButtons();
        log.info("JavaFX stage shown");
    }

    // --------------------------------------------------------------------------------
    // helpers
    // --------------------------------------------------------------------------------
    private Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-title");
        return label;
    }

    /** Creates a step-number badge label. */
    private Label createStepBadge(int num) {
        Label badge = new Label(String.valueOf(num));
        badge.getStyleClass().setAll("step-number");
        return badge;
    }

    private Label hintLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-hint");
        label.setWrapText(true);
        return label;
    }

    private void setBusy(boolean busy) {
        choosePdfButton.setDisable(busy);
        detectButton.setDisable(busy);
        authenticateButton.setDisable(busy);
        cancelButton.setDisable(busy);
        verifyButton.setDisable(busy);
        logoutButton.setDisable(busy);
        progressBar.setVisible(busy);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        if (!busy) {
            refreshPdfButtons();
        } else {
            signButton.setDisable(true);
            saveButton.setDisable(true);
        }
    }

    private void setStatus(String message) {
        Platform.runLater(() -> {
            statusLabel.setText(message);
            statusLabel.getStyleClass().removeAll("status-text--error", "status-text--success");
        });
    }

    private void setSuccess(String message) {
        Platform.runLater(() -> {
            statusLabel.setText(message.startsWith("\u2713") ? message : "\u2713 " + message);
            statusLabel.getStyleClass().remove("status-text--error");
            if (!statusLabel.getStyleClass().contains("status-text--success")) {
                statusLabel.getStyleClass().add("status-text--success");
            }
        });
    }

    private void setError(String message) {
        Platform.runLater(() -> {
            statusLabel.setText(
                    message.startsWith("\u2715") || message.startsWith("\u26A0")
                            ? message : "\u2715 " + message);
            statusLabel.getStyleClass().remove("status-text--success");
            if (!statusLabel.getStyleClass().contains("status-text--error")) {
                statusLabel.getStyleClass().add("status-text--error");
            }
        });
    }

    private void showAlert(Alert.AlertType type, String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    private static List<SigningCertificate> safeCertificates(TokenService tokenService) {
        try {
            return tokenService.getCertificates();
        } catch (IllegalStateException e) {
            return List.of();
        }
    }

    /**
     * Signing is only possible once the token is authenticated and a PDF has
     * been selected; saving is only possible once a signed PDF exists.
     */
    private void refreshPdfButtons() {
        saveButton.setDisable(signedPdfPath == null);
        TokenState state = context == null || !context.isActive()
                ? null
                : context.getBean(TokenService.class).getState();
        boolean authed = state == TokenState.AUTHENTICATED
                || state == TokenState.SIGNATURE_COMPLETE
                || state == TokenState.SIGNATURE_FAILED;
        authenticateButton.setDisable(authed);
        authenticateButton.setText(authed ? "Unlocked" : "Unlock Device");
        pinField.setDisable(authed);
        cancelButton.setDisable(state != TokenState.WAITING_FOR_PIN);
        signButton.setDisable(selectedPdf == null || !authed);
        signButton.setText(authed ? "Sign Document" : "Sign Document — unlock device first");
        highlightStep(state, authed);
    }

    /** Highlights the section the user should act on next (wizard feel). */
    private void highlightStep(TokenState state, boolean authed) {
        VBox active;
        if (state == null || state == TokenState.NO_TOKEN) {
            active = tokenSection;
        } else if (!authed && state == TokenState.WAITING_FOR_PIN) {
            active = pinSection;
        } else if (!authed) {
            active = certSection;
        } else if (signedPdfPath == null) {
            active = pdfSection;
        } else {
            active = null; // all steps complete — nothing highlighted
        }
        for (VBox section : List.of(pdfSection, tokenSection, certSection, pinSection)) {
            if (section == active) {
                if (!section.getStyleClass().contains("card--active")) {
                    section.getStyleClass().add("card--active");
                }
            } else {
                section.getStyleClass().remove("card--active");
            }
        }
        // Update step badges
        updateStepBadge(pdfStepBadge, signedPdfPath != null,
                active == pdfSection);
        boolean tokenDone = state != null && state != TokenState.NO_TOKEN
                && state != TokenState.TOKEN_REMOVED;
        updateStepBadge(tokenStepBadge, tokenDone, active == tokenSection);
        boolean certDone = tokenDone && (state == TokenState.WAITING_FOR_PIN
                || state == TokenState.AUTHENTICATED || state == TokenState.SIGNATURE_COMPLETE
                || state == TokenState.SIGNATURE_FAILED);
        updateStepBadge(certStepBadge, certDone, active == certSection);
        boolean pinDone = state == TokenState.AUTHENTICATED
                || state == TokenState.SIGNATURE_COMPLETE || state == TokenState.SIGNATURE_FAILED;
        updateStepBadge(pinStepBadge, pinDone, active == pinSection);
    }

    private void updateStepBadge(Label badge, boolean done, boolean active) {
        badge.getStyleClass().removeAll("step-number--active", "step-number--done");
        if (done) {
            badge.getStyleClass().add("step-number--done");
        } else if (active) {
            badge.getStyleClass().add("step-number--active");
        }
    }

    // --------------------------------------------------------------------------------
    // step 1: PDF
    // --------------------------------------------------------------------------------
    private void choosePdf() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a PDF to sign");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PDF documents", "*.pdf"));
        java.io.File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }
        loadPdf(file.toPath());
    }

    private void loadPdf(Path pdf) {
        if (pdfLoadInProgress) {
            return;
        }
        pdfLoadInProgress = true;
        selectedPdf = pdf;
        PdfDocumentService pdfService = context.getBean(PdfDocumentService.class);
        Task<PdfDocumentInfo> task = new Task<>() {
            @Override
            protected PdfDocumentInfo call() throws IOException {
                return pdfService.describe(selectedPdf);
            }
        };
        task.setOnSucceeded(e -> {
            pdfLoadInProgress = false;
            PdfDocumentInfo info = task.getValue();
            pdfInfo.setText(info.fileName() + "  ·  " + info.sizeBytes() + " bytes  ·  " + info.lastModified());
            setStatus("\u2713 PDF selected: " + info.fileName());
            refreshPdfButtons();
        });
        task.setOnFailed(e -> {
            pdfLoadInProgress = false;
            setError("Could not read PDF: " + failureMessage(task));
        });
        new Thread(task, "pdf-describe").start();
    }

    private void setupDropTarget(Scene scene, BorderPane target) {
        scene.setOnDragOver(e -> {
            Dragboard db = e.getDragboard();
            if (e.getGestureSource() == null && db.hasFiles() && hasPdfFile(db)) {
                e.acceptTransferModes(TransferMode.COPY);
                if (!target.getStyleClass().contains("drop-target")) {
                    target.getStyleClass().add("drop-target");
                }
            }
            e.consume();
        });
        scene.setOnDragExited(e -> {
            target.getStyleClass().remove("drop-target");
        });
        scene.setOnDragDropped(e -> {
            boolean done = false;
            Dragboard db = e.getDragboard();
            if (db.hasFiles()) {
                java.io.File pdf = db.getFiles().stream()
                        .filter(f -> f.getName().toLowerCase().endsWith(".pdf"))
                        .findFirst().orElse(null);
                if (pdf != null) {
                    loadPdf(pdf.toPath());
                    done = true;
                }
            }
            target.getStyleClass().remove("drop-target");
            e.setDropCompleted(done);
            e.consume();
        });
    }

    private static boolean hasPdfFile(Dragboard db) {
        return db.getFiles().stream().anyMatch(f -> f.getName().toLowerCase().endsWith(".pdf"));
    }

    // --------------------------------------------------------------------------------
    // step 2: token detection
    // --------------------------------------------------------------------------------
    private void detect() {
        if (context == null || !context.isActive()) {
            return;
        }
        setBusy(true);
        setStatus("Detecting token…");
        TokenService tokenService = context.getBean(TokenService.class);
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                tokenService.detect();
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            setBusy(false);
            refreshTokenUi();
            setStatus("\u2713 Token detected");
        });
        task.setOnFailed(e -> {
            setBusy(false);
            refreshTokenUi();
            setError("Token detection failed: " + failureMessage(task));
        });
        new Thread(task, "token-detect").start();
    }

    private void refreshTokenUi() {
        TokenService tokenService = context.getBean(TokenService.class);
        Platform.runLater(() -> {
            TokenState state = tokenService.getState();
            statePill.setText(state.name().replace('_', ' '));
            if (state == TokenState.CERTIFICATE_AVAILABLE
                    && !statusLabel.getStyleClass().contains("status-text--error")) {
                statusLabel.setText("Token detected — enter your PIN and click Authenticate");
            }

            // Update status dot
            statusDot.getStyleClass().removeAll("status-dot--active", "status-dot--pending",
                    "status-dot--error", "status-dot--inactive");
            if (state == TokenState.AUTHENTICATED
                    || state == TokenState.SIGNATURE_COMPLETE) {
                statusDot.getStyleClass().add("status-dot--active");
            } else if (state == TokenState.NO_TOKEN || state == TokenState.TOKEN_REMOVED) {
                statusDot.getStyleClass().add("status-dot--error");
            } else if (state == TokenState.CERTIFICATE_AVAILABLE
                    || state == TokenState.WAITING_FOR_PIN) {
                statusDot.getStyleClass().add("status-dot--pending");
            } else {
                statusDot.getStyleClass().add("status-dot--pending");
            }

            TokenInfo info = tokenService.getTokenInfo();
            tokenInfoLabel.setText(info == null
                    ? "No token"
                    : info.label() + "  ·  " + info.manufacturer() + "  ·  " + info.model());

            List<SigningCertificate> certs = safeCertificates(tokenService);
            List<String> labels = certs.stream()
                    .map(c -> c.getSubject() + "  [" + c.getAlias() + "]")
                    .toList();
            certCombo.getItems().setAll(labels);
            if (!certs.isEmpty()) {
                selectedCert = certs.get(0);
                selectedAlias = certs.get(0).getAlias();
                certCombo.getSelectionModel().select(0);
            } else {
                selectedCert = null;
                selectedAlias = null;
            }
            onCertificateSelected();
            refreshPdfButtons();
        });
    }

    private void onCertificateSelected() {
        TokenService tokenService = context.getBean(TokenService.class);
        int index = certCombo.getSelectionModel().getSelectedIndex();
        if (index < 0) {
            selectedCert = null;
            selectedAlias = null;
            certInfoLabel.setText(" ");
            return;
        }
        List<SigningCertificate> certs = safeCertificates(tokenService);
        if (index >= certs.size()) {
            return;
        }
        selectedCert = certs.get(index);
        selectedAlias = selectedCert.getAlias();
        certInfoLabel.setText("Subject: " + selectedCert.getSubject()
                + "  ·  " + selectedCert.getPublicKeyAlgorithm() + " " + selectedCert.getKeySize()
                + "  ·  " + selectedCert.getSha256Fingerprint());
    }

    // --------------------------------------------------------------------------------
    // step 3: authentication
    // --------------------------------------------------------------------------------
    private void authenticate() {
        if (context == null || !context.isActive()) {
            return;
        }
        TokenService tokenService0 = context.getBean(TokenService.class);
        TokenState state = tokenService0.getState();
        if (state == TokenState.AUTHENTICATED
                || state == TokenState.SIGNATURE_COMPLETE
                || state == TokenState.SIGNATURE_FAILED) {
            log.info("Unlock clicked but token is already authenticated");
            setStatus("\u2713 Security device is already unlocked — ready to sign.");
            refreshTokenUi();
            return;
        }
        if (state != TokenState.CERTIFICATE_AVAILABLE
                && state != TokenState.WAITING_FOR_PIN) {
            setError("Device not ready (state " + state + ") — detect the token first.");
            return;
        }
        char[] pin = pinField.getCharacters() == null ? null
                : pinField.getCharacters().toString().toCharArray();
        if (pin == null || pin.length == 0) {
            log.info("Unlock clicked but PIN field is empty");
            setError("Enter the device PIN first");
            showAlert(Alert.AlertType.WARNING, "PIN required",
                    "Type your security device PIN in the field, then click Unlock.");
            return;
        }
        log.info("Unlock clicked with {} PIN character(s)", pin.length);
        pinField.clear();
        setBusy(true);
        setStatus("Unlocking security device…");
        TokenService tokenService = context.getBean(TokenService.class);
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                try {
                    // Only OPEN the PIN flow when it has not been started yet;
                    // after a failed attempt the state is WAITING_FOR_PIN and
                    // startAuthentication() must not be called again.
                    if (tokenService.getState() == TokenState.CERTIFICATE_AVAILABLE) {
                        tokenService.startAuthentication();
                    }
                    tokenService.authenticate(pin); // TokenService wipes the PIN
                    return null;
                } finally {
                    java.util.Arrays.fill(pin, '\0');
                }
            }
        };
        task.setOnSucceeded(e -> {
            setBusy(false);
            refreshTokenUi();
            setStatus("\u2713 Security device unlocked — ready to sign.");
            log.info("Authentication succeeded");
            showAlert(Alert.AlertType.INFORMATION, "Authentication", "Authenticated. You can now Sign PDF.");
        });
        task.setOnFailed(e -> {
            setBusy(false);
            refreshTokenUi();
            setError("Authentication failed: " + failureMessage(task));
            log.warn("Authentication failed", task.getException());
            showAlert(Alert.AlertType.ERROR, "Authentication failed",
                    failureMessage(task));
        });
        new Thread(task, "token-auth").start();
    }

    private void cancelAuthentication() {
        setBusy(true);
        TokenService tokenService = context.getBean(TokenService.class);
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                tokenService.cancelAuthentication();
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            setBusy(false);
            refreshTokenUi();
            setStatus("PIN entry cancelled");
        });
        task.setOnFailed(e -> {
            setBusy(false);
            refreshTokenUi();
            setError("Cancel failed: " + failureMessage(task));
        });
        new Thread(task, "token-cancel").start();
    }

    private void logout() {
        setBusy(true);
        TokenService tokenService = context.getBean(TokenService.class);
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                tokenService.logout();
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            setBusy(false);
            refreshTokenUi();
            setStatus("Logged out");
        });
        task.setOnFailed(e -> {
            setBusy(false);
            refreshTokenUi();
            setError("Logout failed: " + failureMessage(task));
        });
        new Thread(task, "token-logout").start();
    }

    // --------------------------------------------------------------------------------
    // step 4: sign & verify
    // --------------------------------------------------------------------------------
    private void sign() {
        if (selectedPdf == null || selectedAlias == null) {
            setError("Select a PDF and unlock the security device first");
            return;
        }
        setBusy(true);
        setStatus("Signing…");
        PdfSigningService pdfSigningService = context.getBean(PdfSigningService.class);
        Path pdf = selectedPdf;
        String alias = selectedAlias;
        SigningAlgorithm algorithm = SigningAlgorithm.RSA_SHA256;
        Task<SigningResult> task = new Task<>() {
            @Override
            protected SigningResult call() throws Exception {
                Path temp = Files.createTempFile("signer-", "-signed.pdf");
                try {
                    SigningResult r = pdfSigningService.signPdf(pdf, alias, algorithm, temp);
                    if (Files.size(temp) == 0) {
                        throw new IOException("Signing produced an empty output file");
                    }
                    signedPdfPath = temp;
                    return r;
                } catch (Exception ex) {
                    Files.deleteIfExists(temp);
                    throw ex;
                }
            }
        };
        task.setOnSucceeded(e -> {
            setBusy(false);
            lastSigningResult = task.getValue();
            refreshTokenUi();
            saveButton.setDisable(false);
            log.info("PDF signing succeeded, signedPdfPath={}, size={}", signedPdfPath,
                    (signedPdfPath == null ? -1 : nullSafeSize(signedPdfPath)));
            setStatus("Signed with " + lastSigningResult.algorithm()
                    + " by " + lastSigningResult.signerSubject() + " — verifying…");
            verify();
        });
        task.setOnFailed(e -> {
            setBusy(false);
            refreshTokenUi();
            setError("Signing failed: " + failureMessage(task));
            log.warn("PDF signing failed", task.getException());
        });
        new Thread(task, "pdf-sign").start();
    }

    private long nullSafeSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            return -2;
        }
    }

    private void verify() {
        if (signedPdfPath == null) {
            setError("Sign a PDF first, then verify");
            return;
        }
        setBusy(true);
        setStatus("Verifying…");
        PdfVerificationService verificationService = context.getBean(PdfVerificationService.class);
        Path pdf = signedPdfPath;
        Task<SignatureVerificationResult> task = new Task<>() {
            @Override
            protected SignatureVerificationResult call() throws Exception {
                return verificationService.verifyPdf(pdf);
            }
        };
        task.setOnSucceeded(e -> {
            setBusy(false);
            SignatureVerificationResult vr = task.getValue();
            if (vr.valid()) {
                setSuccess("Signature verified  ·  " + vr.signerSubject());
                log.info("Signature verified successfully");
            } else {
                setError("Signature invalid: " + vr.message());
                log.warn("Signature verification failed: {}", vr.message());
            }
        });
        task.setOnFailed(e -> {
            setBusy(false);
            setError("Verification failed: " + failureMessage(task));
        });
        new Thread(task, "token-verify").start();
    }

    private void saveSignedPdf() {
        if (signedPdfPath == null) {
            setError("Sign a PDF first, then save the signed copy");
            return;
        }
        try {
            if (Files.size(signedPdfPath) == 0) {
                setError("No signed data available — sign a PDF first");
                signedPdfPath = null;
                saveButton.setDisable(true);
                return;
            }
        } catch (IOException ex) {
            setError("Could not read signed PDF: " + ex.getMessage());
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save signed PDF");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PDF documents", "*.pdf"));
        if (selectedPdf != null) {
            String name = selectedPdf.getFileName().toString();
            int dot = name.lastIndexOf('.');
            chooser.setInitialFileName((dot > 0 ? name.substring(0, dot) : name) + "-signed.pdf");
        }
        java.io.File target = chooser.showSaveDialog(stage);
        if (target == null) {
            return;
        }
        try {
            Files.copy(signedPdfPath, target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (Files.size(target.toPath()) == 0) {
                Files.deleteIfExists(target.toPath());
                setError("Could not save signed PDF: output file is empty");
                return;
            }
            setSuccess("Signed PDF saved to " + target.getName());
        } catch (IOException ex) {
            setError("Could not save signed PDF: " + ex.getMessage());
        }
    }

    private void openWorkspace() {
        SigningWorkspace workspace = new SigningWorkspace(
                context.getBean(com.example.signer.token.TokenService.class),
                context.getBean(PdfSigningService.class),
                context.getBean(PdfRenderService.class),
                context.getBean(PdfVerificationService.class));
        workspace.show(stage);
    }

    // --------------------------------------------------------------------------------
    // background token polling (kept from the original placeholder)
    // --------------------------------------------------------------------------------
    private void refreshTokenStateInBackground() {
        if (context == null || !context.isActive()) {
            return;
        }
        TokenService tokenService = context.getBean(TokenService.class);
        Task<TokenState> task = new Task<>() {
            @Override
            protected TokenState call() {
                try {
                    tokenService.detect();
                } catch (Exception e) {
                    log.debug("Token detection failed during startup: {}", e.getMessage());
                }
                return tokenService.getState();
            }
        };
        task.setOnSucceeded(e -> {
            refreshTokenUi();
            if (task.getValue() != null && task.getValue().name().contains("NO_TOKEN")) {
                setStatus("No token detected — insert a token and press “Detect token”");
            }
        });
        new Thread(task, "token-detect-background").start();
    }

    private static String failureMessage(Task<?> task) {
        Throwable t = task.getException();
        return t == null ? "unknown error" : (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
    }

    @Override
    public void stop() {
        log.info("Shutting down application");
        if (context != null) {
            context.close();
        }
    }
}
