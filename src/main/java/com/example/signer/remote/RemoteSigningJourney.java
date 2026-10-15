package com.example.signer.remote;

import com.example.signer.exception.SignerException;
import com.example.signer.pdf.PdfRenderService;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionResponse;
import com.example.signer.server.api.ApiContracts.TaskView;
import com.example.signer.signing.SignatureAppearance;
import com.example.signer.signing.SignaturePlacement;
import com.example.signer.signing.SignatureStampRenderer;
import com.example.signer.token.SigningAlgorithm;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.TokenService;
import com.example.signer.ui.ErrorMessages;
import com.example.signer.ui.ThemeManager;
import com.example.signer.ui.kit.DocumentReviewPane;
import com.example.signer.ui.kit.SignFlowUi;
import com.example.signer.ui.kit.SignaturePropertiesView;
import com.example.signer.ui.support.UiSupport;
import com.example.signer.ui.workspace.PlacementViewer;
import com.example.signer.verification.SignatureProperties;
import com.example.signer.verification.SignatureStatus;
import com.example.signer.verification.SignatureVerificationResult;
import com.example.signer.workflow.WorkflowSigningService;
import com.example.signer.ui.support.SignedPdfExport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * The remote signing journey, one screen at a time: the workspace (the shared
 * placement viewer + smart card, appearance, preview and existing signatures)
 * → Confirm (stamp preview, summary, consent, PIN) → progress → success or
 * failure. Every value shown comes from the downloaded revision, its
 * verification, the real certificate on the token or the server's answer —
 * nothing is invented. The PIN is read only on the Confirm screen and handed
 * to the LOCAL token.
 *
 * <p>Split out of {@link RemoteWorkflowDialog} (which keeps sign-in, task
 * lists, task details and navigation); the dialog is reached only through
 * the narrow {@link Host} interface.
 */
final class RemoteSigningJourney {

    /** What the journey needs from the window that hosts it. */
    interface Host {
        Stage window();

        VBox content();

        Label status();

        Button backButton();

        WorkflowApiClient client();

        boolean guestMode();

        /** "Signing as": the invited address for a guest, else the account. */
        String signingAsLabel();

        String signedInUser();

        /** Records a screen for Back (see the dialog's navigation). */
        void navigateTo(Runnable screen);

        /** Shows {@code screen} replacing the current one (no extra Back step). */
        void showInPlace(Runnable screen);

        void goBack();

        void showTasks();

        /** Terminal screen: forget the Back history. */
        void clearHistory();

        void runInBackground(Task<?> task);
    }

    private final Host host;
    private final TokenService tokenService;
    private final WorkflowSigningService signingService;
    private final PdfRenderService renderService;

    RemoteSigningJourney(Host host, TokenService tokenService,
                         WorkflowSigningService signingService, PdfRenderService renderService) {
        this.host = host;
        this.tokenService = tokenService;
        this.signingService = signingService;
        this.renderService = renderService;
    }

    /** The signing steps, bound to the current session. */
    private RemoteSigningService remoteSigning() {
        return new RemoteSigningService(host.client(), signingService, tokenService);
    }

    /** True while a signature is being created/submitted: no Back, no close. */
    boolean isSigning() {
        return signingInProgress;
    }

    /**
     * Back pressed: returns true when the journey handled it (signing in
     * progress, or a box being drawn is cancelled instead).
     */
    boolean consumeBack() {
        if (signingInProgress) {
            return true;
        }
        if (activeViewer != null && activeViewer.placingProperty().get()) {
            activeViewer.cancelPlacing();
            return true;
        }
        return false;
    }

    private static final List<String> SIGN_STEPS =
            List.of("Document", "Signature", "Confirm");
    private static final List<String> REASONS = List.of("Document approval",
            "Contract approval", "Reviewed", "Accepted", "Authorized", "Other…");
    private static final float STAMP_W_PTS = 170f;
    private static final float STAMP_H_PTS = 55f;

    /** Review package of the journey in progress (downloaded revision). */
    private RemoteSigningService.ReviewPackage review;
    /** True while a signature is being created/submitted (no Back, no close). */
    private boolean signingInProgress;
    /** The viewer on screen (closed when leaving the workspace). */
    private PlacementViewer activeViewer;
    /** Choices kept while moving between the workspace and Confirm. */
    private SignaturePlacement chosenPlacement;
    private String chosenReason = REASONS.get(0);
    private String chosenOtherReason = "";
    private String chosenLocation = "";
    private SigningCardCheck lastCardCheck;

    /** Leaves the signing screens: frees the viewer and the window space. */
    void leaveSigning() {
        if (activeViewer != null) {
            activeViewer.close();
            activeViewer = null;
        }
    }

    /** Step strip on top of every signing screen (same chips as the local workspace). */
    private HBox signingStrip(int current) {
        HBox strip = new HBox(6);
        strip.getStyleClass().add("mws-strip");
        strip.setAlignment(Pos.CENTER);
        for (int i = 0; i < SIGN_STEPS.size(); i++) {
            Label chip = new Label((i + 1) + " · " + SIGN_STEPS.get(i));
            chip.getStyleClass().addAll("mws-chip", i < current ? "mws-chip--done"
                    : i == current ? "mws-chip--active" : "mws-chip--pending");
            strip.getChildren().add(chip);
            if (i < SIGN_STEPS.size() - 1) {
                FontIcon arrow = new FontIcon(FontAwesomeSolid.CHEVRON_RIGHT);
                arrow.getStyleClass().add("mws-strip-arrow");
                strip.getChildren().add(arrow);
            }
        }
        HBox row = new HBox(strip);
        row.setAlignment(Pos.CENTER);
        row.getStyleClass().add("mws-strip-row");
        return row;
    }

    /** The workspace needs room: grow the window once (never past the screen). */
    void ensureWorkspaceSize() {
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        double w = Math.min(1280, screen.getWidth() - 40);
        double h = Math.min(820, screen.getHeight() - 40);
        if (host.window().getWidth() < w - 1 || host.window().getHeight() < h - 1) {
            host.window().setWidth(Math.max(host.window().getWidth(), w));
            host.window().setHeight(Math.max(host.window().getHeight(), h));
            host.window().centerOnScreen();
        }
    }

    /** Entry point from the task screen: download the revision, then the workspace. */
    void showReview(TaskView task) {
        host.navigateTo(() -> showReview(task));
        leaveSigning();
        host.status().setText("");
        chosenPlacement = null;
        lastCardCheck = null;
        host.content().getChildren().setAll(signingStrip(0), RemoteScreens.centered(RemoteScreens.loadingCard("Opening the document…")));
        host.runInBackground(new Task<RemoteSigningService.ReviewPackage>() {
            @Override
            protected RemoteSigningService.ReviewPackage call() throws Exception {
                Path work = Files.createTempDirectory("signflow-review");
                return remoteSigning()
                        .prepareReview(task.workflowId(), work);
            }

            @Override
            protected void succeeded() {
                review = getValue();
                host.showInPlace(RemoteSigningJourney.this::showWorkspace);
            }

            @Override
            protected void failed() {
                showFailure(getException(), null, () -> showReview(task));
            }
        });
    }

    /** Step 1 — the workspace: see the document, place the signature. */
    private void showWorkspace() {
        host.navigateTo(this::showWorkspace);
        leaveSigning();
        ensureWorkspaceSize();
        host.status().setText("");
        TaskView task = review.task();
        var doc = review.document();
        var verification = doc.verification();
        var step = review.step();

        // Can this revision be signed? Same rules as the signing step.
        String blocker = null;
        if (doc.parseFailure() != null) {
            blocker = ErrorMessages.generic(doc.parseFailure());
        } else if (doc.effective() == null || step == null) {
            blocker = "This document does not carry SignFlow signing information for you.";
        } else {
            blocker = existingSignaturesProblem(verification);
        }

        PlacementViewer viewer =
                new PlacementViewer(renderService,
                        review.pdf(), review.otherPlacements(),
                        step == null ? null : step.placement(), chosenPlacement);
        activeViewer = viewer;
        HBox.setHgrow(viewer.getRoot(), Priority.ALWAYS);

        // ---- right panel (same sections as the local signer panel) ---------
        Label title = new Label("Signer " + task.signerOrder() + " of " + task.signers().size());
        title.getStyleClass().add("mws-panel-title");
        Label pill = new Label("Your turn");
        pill.getStyleClass().addAll("status-pill", "status-pill--active");
        HBox head = new HBox(8, title, pill);
        head.setAlignment(Pos.CENTER_LEFT);

        Label identityName = new Label(host.signingAsLabel());
        identityName.getStyleClass().add("mws-identity-name");
        Label identityDetails = new Label(SigningTaskModels.readableTitle(task.documentName())
                + " · version " + task.documentRevision());
        identityDetails.getStyleClass().add("section-hint");
        identityDetails.setWrapText(true);
        VBox identityBox = new VBox(4, identityName, identityDetails);
        identityBox.getStyleClass().add("mws-identity-box");

        Label placementLabel = new Label();
        placementLabel.getStyleClass().add("section-hint");
        placementLabel.setWrapText(true);
        Label overlapLabel = new Label("⚠ This placement overlaps an existing signature.");
        overlapLabel.getStyleClass().add("mws-overlap-warning");
        overlapLabel.setWrapText(true);

        // Smart card
        VBox cardBox = new VBox(6);
        // Appearance
        ComboBox<String> reasonCombo = new ComboBox<>();
        reasonCombo.getItems().setAll(REASONS);
        reasonCombo.setValue(chosenReason);
        reasonCombo.setMaxWidth(Double.MAX_VALUE);
        TextField reasonOther = new TextField(chosenOtherReason);
        reasonOther.setPromptText("Custom reason");
        TextField locationField = new TextField(chosenLocation);
        locationField.setPromptText("Location (optional, e.g. Rabat, Morocco)");
        locationField.setMaxWidth(Double.MAX_VALUE);
        Label appearanceNote = new Label("The box is only the visible part. The signature"
                + " that counts is the digital signature made by your smart card; it"
                + " protects the whole document.");
        appearanceNote.getStyleClass().add("section-hint");
        appearanceNote.setWrapText(true);
        VBox appearanceBox = new VBox(4, new Label("Reason"), reasonCombo, reasonOther,
                new Label("Location"), locationField, appearanceNote);
        // Preview
        ImageView preview = new ImageView();
        preview.setFitWidth(250);
        preview.setPreserveRatio(true);
        preview.getStyleClass().add("mws-preview");
        Label previewNote = new Label("The date is filled in when you sign.");
        previewNote.getStyleClass().add("section-hint");
        VBox previewBox = new VBox(4, preview, previewNote);

        // Existing signatures
        VBox sigBox = new VBox(6);
        List<SignatureVerificationResult.SignatureDetail> sigs =
                verification == null || verification.signatures() == null
                        ? List.of() : verification.signatures();
        if (sigs.isEmpty()) {
            Label none = new Label("None yet — you are the first to sign.");
            none.getStyleClass().add("section-hint");
            sigBox.getChildren().add(none);
        }
        for (var sig : sigs) {
            var status = SignatureStatus.of(sig, verification);
            String who = SignatureProperties.commonName(
                    sig.properties() != null ? sig.properties().certSubject() : sig.signerSubject());
            FontIcon icon = new FontIcon(status.isValid()
                    ? FontAwesomeSolid.CHECK_CIRCLE : FontAwesomeSolid.EXCLAMATION_CIRCLE);
            icon.setIconColor(Color.web(status.isValid() ? "#16A34A" : "#DC2626"));
            Label name = new Label(who + " — " + status.title(), icon);
            name.setWrapText(true);
            Hyperlink details = new Hyperlink("Details");
            details.setOnAction(e -> SignaturePropertiesView.show(
                    host.window(), sig, verification, sigs.size()));
            sigBox.getChildren().add(new VBox(0, name, details));
        }

        // Actions
        ToggleButton placeBtn = new ToggleButton("Place Signature",
                new FontIcon(FontAwesomeSolid.PEN_FANCY));
        placeBtn.getStyleClass().add("btn-secondary");
        placeBtn.setMaxWidth(Double.MAX_VALUE);
        placeBtn.setOnAction(e -> viewer.togglePlacing());
        Button clearBtn = new Button("Clear", new FontIcon(FontAwesomeSolid.ERASER));
        clearBtn.getStyleClass().add("btn-ghost");
        clearBtn.setOnAction(e -> viewer.clearPlacement());
        Button next = new Button("Continue to sign", new FontIcon(FontAwesomeSolid.ARROW_RIGHT));
        next.setContentDisplay(ContentDisplay.RIGHT);
        next.getStyleClass().add("btn-primary");
        next.setMaxWidth(Double.MAX_VALUE);
        Label whyDisabled = new Label();
        whyDisabled.getStyleClass().add("section-hint");
        whyDisabled.setWrapText(true);
        VBox actionsBox = new VBox(6, placeBtn, clearBtn, next, whyDisabled);
        if (viewer.isLocked()) {
            placeBtn.setVisible(false);
            placeBtn.setManaged(false);
            clearBtn.setVisible(false);
            clearBtn.setManaged(false);
        }

        // ---- live updates ---------------------------------------------------
        final String blockerF = blocker;
        Runnable refresh = () -> {
            var p = viewer.getPlacement();
            placementLabel.setText(p == null
                    ? "Signature: not placed yet — click Place Signature, then drag on the page."
                    : "Signature: page " + (p.pageIndex() + 1) + " · "
                            + Math.round(p.width()) + "×" + Math.round(p.height()) + " pt"
                            + (viewer.isLocked() ? " (position chosen by the sender)" : ""));
            boolean overlap = viewer.overlapsExisting();
            overlapLabel.setVisible(overlap);
            overlapLabel.setManaged(overlap);
            boolean other = "Other…".equals(reasonCombo.getValue());
            reasonOther.setVisible(other);
            reasonOther.setManaged(other);
            chosenReason = reasonCombo.getValue();
            chosenOtherReason = reasonOther.getText();
            chosenLocation = locationField.getText();
            chosenPlacement = viewer.isLocked() ? null : p;
            preview.setImage(stampPreview());
            boolean cardOk = lastCardCheck != null && lastCardCheck.state().canSign();
            String why = blockerF != null ? "This document can't be signed."
                    : p == null ? "Place your signature on the document first."
                    : !cardOk ? "Connect your smart card first."
                    : "";
            whyDisabled.setText(why);
            next.setDisable(!why.isEmpty());
        };
        // The current step follows what the signer is doing: reviewing the
        // Document until a box is placed, then the Signature step.
        HBox stripHolder = new HBox();
        stripHolder.setAlignment(Pos.CENTER);
        stripHolder.getChildren().setAll(signingStrip(viewer.getPlacement() == null ? 0 : 1));
        viewer.placementProperty().addListener((o, a, b) -> {
            stripHolder.getChildren().setAll(signingStrip(b == null ? 0 : 1));
            refresh.run();
        });
        viewer.placingProperty().addListener((o, a, now) -> {
            placeBtn.setSelected(now);
            placeBtn.setText(now ? "Drag on the page…" : "Place Signature");
        });
        viewer.statusProperty().addListener((o, a, text) -> host.status().setText(text));
        reasonCombo.valueProperty().addListener((o, a, b) -> refresh.run());
        reasonOther.textProperty().addListener((o, a, b) -> refresh.run());
        locationField.textProperty().addListener((o, a, b) -> refresh.run());
        next.setOnAction(e -> {
            refresh.run();
            if (!next.isDisabled()) {
                showConfirm(viewer.getPlacement());
            }
        });

        Runnable[] check = new Runnable[1];
        check[0] = () -> {
            cardBox.getChildren().setAll(SignFlowUi.note(
                    FontAwesomeSolid.SYNC, SignFlowUi.Tone.INFO,
                    "Looking for your smart card…"));
            host.runInBackground(new Task<SigningCardCheck>() {
                @Override
                protected SigningCardCheck call() {
                    return SigningCardCheck.run(tokenService, review.requiredCertificate());
                }

                @Override
                protected void succeeded() {
                    lastCardCheck = getValue();
                    renderCardCheck(cardBox, lastCardCheck, check[0]);
                    refresh.run();
                }

                @Override
                protected void failed() {
                    lastCardCheck = new SigningCardCheck(SigningCardCheck.State.ERROR, null);
                    renderCardCheck(cardBox, lastCardCheck, check[0]);
                    refresh.run();
                }
            });
        };

        TitledPane cardPane = new TitledPane("Smart card", cardBox);
        TitledPane appearancePane = new TitledPane("Signature appearance", appearanceBox);
        TitledPane previewPane = new TitledPane("Preview", previewBox);
        TitledPane sigPane = new TitledPane("Signatures in this document (" + sigs.size() + ")", sigBox);
        TitledPane actionsPane = new TitledPane("Actions", actionsBox);
        for (TitledPane pane : List.of(cardPane, appearancePane, previewPane, sigPane, actionsPane)) {
            pane.setExpanded(true);
            pane.setCollapsible(false);
        }
        VBox inner = new VBox(8, identityBox, placementLabel, overlapLabel);
        if (blocker != null) {
            inner.getChildren().add(SignFlowUi.note(
                    FontAwesomeSolid.EXCLAMATION_TRIANGLE,
                    SignFlowUi.Tone.ERROR,
                    "This document can't be signed. " + blocker));
        }
        inner.getChildren().addAll(actionsPane, cardPane, appearancePane, previewPane, sigPane);
        inner.getStyleClass().add("mws-panel");
        ScrollPane panelScroll = new ScrollPane(inner);
        panelScroll.setFitToWidth(true);
        panelScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        panelScroll.getStyleClass().add("mws-panel-scroll");
        VBox.setVgrow(panelScroll, Priority.ALWAYS);
        VBox panel = new VBox(head, panelScroll);
        panel.getStyleClass().add("mws-panel-root");
        panel.setPrefWidth(310);
        panel.setMinWidth(280);

        HBox center = new HBox(10, viewer.getRoot(), panel);
        VBox.setVgrow(center, Priority.ALWAYS);
        host.content().getChildren().setAll(stripHolder, center);
        refresh.run();
        check[0].run();
        host.status().setText(viewer.isLocked()
                ? "The sender chose where your signature goes. Review the document, then continue."
                : "Review the document, then click Place Signature and drag on the page.");
    }

    /**
     * Why the signatures already in the document block signing, or null.
     * A document with NO signatures yet (the first signer's version 0) is
     * fine: the verifier reports it as "PDF is not signed", which is not a
     * failed signature. Same rule as the signing step itself
     * ({@link RemoteSigningService}): only an existing signature that does
     * not verify blocks.
     */
    static String existingSignaturesProblem(SignatureVerificationResult verification) {
        if (verification == null || verification.signatures() == null
                || verification.signatures().isEmpty()) {
            return null;
        }
        if (verification.signatures().stream().anyMatch(s -> !s.valid())) {
            return "An existing signature in this document is invalid.";
        }
        if (!verification.valid()) {
            return "The document's existing signatures did not verify: "
                    + verification.message();
        }
        return null;
    }

    /** The real stamp, drawn by the same renderer the signing step uses. */
    private Image stampPreview() {
        try {
            SigningCertificate cert = lastCardCheck == null ? null : lastCardCheck.certificate();
            String subject = cert != null ? cert.getSubject()
                    : review.requiredCertificate() == null ? null : review.requiredCertificate().subject();
            String cn = subject == null ? host.signedInUser()
                    : SignatureProperties.commonName(subject);
            SigningAlgorithm algorithm = cert != null
                    && "EC".equals(cert.getPublicKeyAlgorithm())
                    ? SigningAlgorithm.ECDSA_SHA256
                    : SigningAlgorithm.RSA_SHA256;
            var hint = appearanceHint();
            var appearance = new SignatureAppearance(
                    hint.reason(), hint.location(), true, true, true,
                    review.step() == null ? null : review.step().displayName());
            SignaturePlacement p = activeViewer == null
                    ? null : activeViewer.getPlacement();
            float w = p == null ? STAMP_W_PTS : p.width();
            float h = p == null ? STAMP_H_PTS : p.height();
            return SwingFXUtils.toFXImage(
                    SignatureStampRenderer.render(w, h, cn,
                            algorithm, appearance), null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The real signing device, as reported by the token (no PIN needed). */
    private String signingMethodLabel() {
        String device = null;
        try {
            device = tokenService.getTokenInfo().label();
        } catch (RuntimeException ignored) {
            // token not active: the label is simply not shown
        }
        return "Smart card / USB token" + (device == null || device.isBlank() ? "" : " (" + device + ")")
                + " — PDF digital signature, PKCS#7 detached, SHA-256";
    }

    /** Reason/location exactly as they will be written into the signature. */
    private WorkflowSigningService.SignatureAppearanceHint appearanceHint() {
        String reason = "Other…".equals(chosenReason) ? chosenOtherReason : chosenReason;
        return new WorkflowSigningService.SignatureAppearanceHint(
                reason == null || reason.isBlank() ? null : reason.trim(),
                chosenLocation == null || chosenLocation.isBlank() ? null : chosenLocation.trim());
    }

    private void renderCardCheck(VBox box, SigningCardCheck result, Runnable recheck) {
        SigningCardCheck.State state = result.state();
        SignFlowUi.Tone tone = state.canSign()
                ? SignFlowUi.Tone.OK
                : state == SigningCardCheck.State.NOT_DETECTED
                ? SignFlowUi.Tone.INFO
                : SignFlowUi.Tone.WARN;
        box.getChildren().setAll(SignFlowUi.note(
                state.canSign() ? FontAwesomeSolid.ID_CARD : FontAwesomeSolid.CREDIT_CARD,
                tone, state.title() + ". " + state.explanation()));
        SigningCertificate cert = result.certificate();
        if (cert != null) {
            box.getChildren().add(certificateDetails(cert));
        }
        if (!state.canSign()) {
            Button again = SignFlowUi.secondary("Check again");
            again.setOnAction(e -> recheck.run());
            box.getChildren().add(again);
        }
    }

    private static Node certificateDetails(SigningCertificate cert) {
        String cn = SignatureProperties.commonName(cert.getSubject());
        String issuer = SignatureProperties.commonName(cert.getIssuer());
        boolean expired = cert.getValidUntil() != null
                && cert.getValidUntil().isBefore(Instant.now());
        return SignFlowUi.details(
                "Signing as", cn,
                "Email", SignatureProperties.email(cert.getSubject()),
                "Issued by", issuer,
                "Valid until", SignFlowUi.dateTime(cert.getValidUntil())
                        + (expired ? "  (expired)" : ""));
    }

    /** Step 2 — the checkpoint: stamp preview, summary, consent, PIN, Sign. */
    private void showConfirm(SignaturePlacement placement) {
        host.navigateTo(() -> showConfirm(placement));
        Image stamp = stampPreview();
        leaveSigning();
        host.status().setText("");
        TaskView task = review.task();
        SigningCertificate cert = lastCardCheck == null ? null : lastCardCheck.certificate();
        var hint = appearanceHint();

        ImageView preview = new ImageView(stamp);
        preview.setFitWidth(380);
        preview.setPreserveRatio(true);
        preview.getStyleClass().add("mws-checkpoint-preview");

        CheckBox consent = new CheckBox("I confirm that I have reviewed this document and want to sign it.");
        consent.setWrapText(true);
        consent.getStyleClass().add("mws-checkpoint-confirm");
        PasswordField pin = new PasswordField();
        pin.setPromptText("Smart card PIN");
        pin.setMaxWidth(260);
        Button sign = SignFlowUi.primary("Sign Document",
                FontAwesomeSolid.FILE_SIGNATURE);
        sign.setMaxWidth(Region.USE_PREF_SIZE);
        Runnable refresh = () -> sign.setDisable(!consent.isSelected() || pin.getText().isEmpty());
        consent.selectedProperty().addListener((o, a, b) -> refresh.run());
        pin.textProperty().addListener((o, a, b) -> refresh.run());
        refresh.run();
        Button change = SignFlowUi.secondary("Back");
        change.setOnAction(e -> host.goBack());

        sign.setOnAction(e -> {
            char[] secret = pin.getText().toCharArray();
            pin.clear();
            startSigning(task, placement, hint, secret, cert);
        });

        Label warning = new Label("Once signed, this document version cannot be modified"
                + " without affecting the signature.");
        warning.getStyleClass().add("mws-checkpoint-warning");
        warning.setWrapText(true);

        HBox buttons = new HBox(10, change, sign);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        VBox card = SignFlowUi.card(
                SignFlowUi.title("Confirm your signature"),
                preview,
                SignFlowUi.details(
                        "Document", SigningTaskModels.readableTitle(task.documentName()),
                        "Version", "Revision " + task.documentRevision(),
                        "Signing as", host.signingAsLabel(),
                        "Certificate", cert == null ? null
                                : SignatureProperties
                                        .commonName(cert.getSubject())
                                + " — issued by "
                                + SignatureProperties
                                        .commonName(cert.getIssuer()),
                        "Signing method", signingMethodLabel(),
                        "Signing time", "Recorded when you click Sign Document"
                                + " (this computer's clock)",
                        "Page", String.valueOf(placement.pageIndex() + 1),
                        "Box", Math.round(placement.width()) + " × "
                                + Math.round(placement.height()) + " pt at ("
                                + Math.round(placement.x()) + ", "
                                + Math.round(placement.y()) + ")",
                        "Reason", hint.reason() == null ? "Document signed with SignFlow"
                                : hint.reason(),
                        "Location", hint.location()),
                warning,
                consent,
                RemoteScreens.sectionLabel("Enter your smart card PIN"),
                pin,
                SignFlowUi.note(FontAwesomeSolid.LOCK,
                        SignFlowUi.Tone.INFO,
                        "Your PIN and private key never leave this computer. Only the"
                                + " signed document is sent to the SignFlow server."),
                buttons);
        host.content().getChildren().setAll(signingStrip(1), RemoteScreens.centered(card));
        consent.requestFocus();
    }

    /** Progress: runs the signing step off the FX thread. */
    private void startSigning(TaskView task, SignaturePlacement placement,
                              WorkflowSigningService.SignatureAppearanceHint hint, char[] pin,
                              SigningCertificate expectedCert) {
        int reviewedRevision = task.documentRevision();
        signingInProgress = true;
        host.backButton().setDisable(true);
        host.status().setText("");
        RemoteSigningService.Stage[] stages = RemoteSigningService.Stage.values();
        VBox rows = new VBox(10);
        Label[] labels = new Label[stages.length - 1];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = new Label(stages[i].label(), new FontIcon(FontAwesomeSolid.CIRCLE));
            labels[i].getStyleClass().addAll("sf-progress-row", "sf-progress-pending");
            rows.getChildren().add(labels[i]);
        }
        ProgressBar bar = new ProgressBar(0);
        bar.setMaxWidth(Double.MAX_VALUE);
        host.content().getChildren().setAll(signingStrip(1), RemoteScreens.centered(SignFlowUi.card(
                SignFlowUi.title("Signing your document…"),
                SignFlowUi.text(
                        "Keep your smart card connected. This usually takes a few seconds."),
                bar, rows)));

        AtomicReference<RemoteSigningService.Stage> reached = new AtomicReference<>();
        AtomicReference<Path> signedFile = new AtomicReference<>();
        RemoteSigningService.Listener listener = new RemoteSigningService.Listener() {
            @Override
            public void stage(RemoteSigningService.Stage stage) {
                reached.set(stage);
                Platform.runLater(() -> {
                    int at = stage.ordinal();
                    for (int i = 0; i < labels.length; i++) {
                        labels[i].getStyleClass().removeAll("sf-progress-pending",
                                "sf-progress-current", "sf-progress-done");
                        boolean finished = i < at;
                        labels[i].getStyleClass().add(finished ? "sf-progress-done"
                                : i == at ? "sf-progress-current" : "sf-progress-pending");
                        labels[i].setGraphic(new FontIcon(finished ? FontAwesomeSolid.CHECK_CIRCLE
                                : i == at ? FontAwesomeSolid.SPINNER : FontAwesomeSolid.CIRCLE));
                    }
                    bar.setProgress((double) at / labels.length);
                });
            }

            @Override
            public void signedFile(Path signedPdf) {
                signedFile.set(signedPdf);
            }
        };

        host.runInBackground(new Task<SignedOutcome>() {
            @Override
            protected SignedOutcome call() throws Exception {
                Path work = Files.createTempDirectory("signflow-sign");
                SignatureSubmissionResponse response = remoteSigning()
                        .signRemoteStep(task.workflowId(), pin, (pdf, order) -> placement,
                                hint, reviewedRevision, work, listener);
                // Re-verify the signed file locally so the success screen shows
                // the REAL status of the new signature, not an assumption.
                SignatureVerificationResult verification = null;
                Path file = signedFile.get();
                if (file != null) {
                    try {
                        verification = signingService.load(file).verification();
                    } catch (RuntimeException ex) {
                        verification = null;
                    }
                }
                return new SignedOutcome(response, file, verification);
            }

            @Override
            protected void succeeded() {
                signingInProgress = false;
                host.backButton().setDisable(false);
                showSuccess(getValue(), expectedCert);
            }

            @Override
            protected void failed() {
                Arrays.fill(pin, '\0');
                signingInProgress = false;
                host.backButton().setDisable(false);
                showFailure(getException(), reached.get(), () -> showReview(task));
            }
        });
    }

    private record SignedOutcome(SignatureSubmissionResponse response, Path signedPdf,
                                 SignatureVerificationResult
                                         verification) {
    }

    private void showSuccess(SignedOutcome outcome, SigningCertificate expectedCert) {
        // Terminal screen of the journey: Back leaves for the tasks list.
        host.clearHistory();
        var verification = outcome.verification();
        SignatureVerificationResult.SignatureDetail mine = null;
        if (verification != null && verification.signatures() != null
                && !verification.signatures().isEmpty()) {
            var sigs = verification.signatures();
            mine = sigs.get(sigs.size() - 1);
            if (expectedCert != null) {
                for (var sig : sigs) {
                    if (sig.signerCertFingerprint() != null && sig.signerCertFingerprint()
                            .replace(":", "").equalsIgnoreCase(
                                    expectedCert.getSha256Fingerprint().replace(":", ""))) {
                        mine = sig;
                    }
                }
            }
        }
        SignatureStatus status = mine == null ? null
                : SignatureStatus.of(mine, verification);
        boolean completed = outcome.response().completed();

        Button viewDoc = SignFlowUi.secondary("View Signed Document");
        viewDoc.setDisable(outcome.signedPdf() == null);
        String documentName = review == null ? null : review.task().documentName();
        viewDoc.setOnAction(e -> openDocumentWindow(outcome.signedPdf(), verification,
                documentName));
        Button download = SignFlowUi.secondary("Download Signed PDF…");
        download.setDisable(outcome.signedPdf() == null);
        download.setOnAction(e -> downloadSigned(outcome.signedPdf(), documentName));
        Button viewSig = SignFlowUi.secondary("View Signature Details");
        final var detail = mine;
        viewSig.setDisable(detail == null);
        viewSig.setOnAction(e -> SignaturePropertiesView.show(
                host.window(), detail, verification, verification.signatures().size()));
        Button close = SignFlowUi.primary("Close", null);
        close.setMaxWidth(Region.USE_PREF_SIZE);
        close.setOnAction(e -> {
            leaveSigning();
            if (host.guestMode()) {
                host.window().close();
            } else {
                host.showTasks();
            }
        });

        VBox panel = SignFlowUi.statePanel(
                FontAwesomeSolid.CHECK_CIRCLE, SignFlowUi.Tone.OK,
                "Document signed successfully",
                "Your digital signature has been successfully added to the document."
                        + (completed ? " All signatures are now complete."
                                : " The next signer will be asked to sign on their own computer."),
                download, viewDoc, viewSig, close);
        panel.getChildren().add(3, SignFlowUi.details(
                "Document", review == null ? null
                        : SigningTaskModels.readableTitle(review.task().documentName()),
                "Signer", mine == null ? null
                        : SignatureProperties.commonName(
                                mine.properties() != null ? mine.properties().certSubject()
                                        : mine.signerSubject()),
                "Signing time", mine == null || mine.signedAt() == null ? null
                        : SignFlowUi.dateTime(mine.signedAt())
                                + "  (this computer's clock)",
                "Signature status", status == null
                        ? "Not available — the signed file could not be re-checked on"
                                + " this computer"
                        : status.title(),
                "Server", "Accepted as revision " + outcome.response().acceptedRevision()));
        host.content().getChildren().setAll(signingStrip(SIGN_STEPS.size()), RemoteScreens.centered(panel));
        host.status().setText("");
    }

    /**
     * "Open Document" for a completed (or any) task: downloads the current
     * revision, verifies it on this computer and shows it with each
     * signature's REAL validation result and properties.
     */
    void openCurrentDocument(String workflowId) {
        host.status().setText("Opening the document…");
        host.runInBackground(new Task<Object[]>() {
            @Override
            protected Object[] call() throws Exception {
                byte[] pdf = host.client().document(workflowId);
                Path dir = Files.createTempDirectory("signflow-view");
                Path file = dir.resolve(workflowId + ".pdf");
                Files.write(file, pdf);
                SignatureVerificationResult verification;
                try {
                    verification = signingService.load(file).verification();
                } catch (RuntimeException e) {
                    verification = null;
                }
                String name;
                try {
                    name = host.client().task(workflowId).documentName();
                } catch (RuntimeException e) {
                    name = null; // only used for the suggested download name
                }
                return new Object[]{file, verification, name};
            }

            @Override
            protected void succeeded() {
                host.status().setText("");
                openDocumentWindow((Path) getValue()[0],
                        (SignatureVerificationResult) getValue()[1], (String) getValue()[2]);
            }

            @Override
            protected void failed() {
                host.status().setText("The document could not be retrieved. "
                        + ErrorMessages.generic(getException()));
            }
        });
    }

    /** Saves a copy of the signed PDF where the user chooses. */
    private void downloadSigned(Path signedPdf, String documentName) {
        try {
            SignedPdfExport.saveCopy(host.window(), signedPdf, documentName)
                    .ifPresent(saved -> host.status().setText("Signed PDF saved to " + saved));
        } catch (IOException ex) {
            host.status().setText("Could not save the signed PDF: " + ex.getMessage());
        }
    }

    private void openDocumentWindow(Path pdf, SignatureVerificationResult v,
                                    String documentName) {
        Stage window = new Stage();
        window.initOwner(host.window());
        window.setTitle("SignFlow — Signed document");
        var pane = new DocumentReviewPane(renderService, pdf, v, documentName);
        window.setOnHidden(e -> pane.close());
        Scene scene = new Scene(pane.getRoot(), 1000, 720);
        ThemeManager.instance().attach(scene);
        window.setScene(scene);
        UiSupport.applyAppIcon(window);
        window.show();
    }

    /**
     * Error UX: one plain sentence, whether the document changed, and the
     * next action. Raw Java exceptions are never shown (see
     * {@link ErrorMessages#generic}); the technical detail goes to the log.
     */
    void showFailure(Throwable error, RemoteSigningService.Stage reached, Runnable retry) {
        boolean maybeSubmitted = reached == RemoteSigningService.Stage.SUBMIT
                || reached == RemoteSigningService.Stage.DONE;
        String headline = "SignFlow couldn't complete the signature.";
        String consequence = maybeSubmitted
                ? "SignFlow couldn't confirm whether the server received your signed"
                        + " document. Open the signing task again to check its status"
                        + " before retrying."
                : "Your document has not been changed.";
        String reason = ErrorMessages.generic(error);

        Label detailsText = SignFlowUi.text(
                "What happened: " + reason
                        + (error instanceof SignerException se
                        ? "\nError code: " + se.getErrorCode() : "")
                        + (reached == null ? "" : "\nStopped while: " + reached.label()));
        detailsText.setVisible(false);
        detailsText.setManaged(false);

        Button tryAgain = SignFlowUi.primary("Try Again", null);
        tryAgain.setMaxWidth(Region.USE_PREF_SIZE);
        tryAgain.setOnAction(e -> retry.run());
        Button details = SignFlowUi.secondary("View Details");
        details.setOnAction(e -> {
            boolean show = !detailsText.isVisible();
            detailsText.setVisible(show);
            detailsText.setManaged(show);
            details.setText(show ? "Hide Details" : "View Details");
        });
        Button tasks = SignFlowUi.secondary(host.guestMode()
                ? "Back to the signing task" : "Back to signing tasks");
        tasks.setOnAction(e -> {
            leaveSigning();
            host.showTasks();
        });

        VBox panel = SignFlowUi.statePanel(
                FontAwesomeSolid.EXCLAMATION_TRIANGLE, SignFlowUi.Tone.ERROR,
                headline, consequence, tryAgain, details, tasks);
        panel.getChildren().add(detailsText);
        host.content().getChildren().setAll(RemoteScreens.centered(panel));
        host.status().setText("");
    }
}
