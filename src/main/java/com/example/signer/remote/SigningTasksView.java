package com.example.signer.remote;

import com.example.signer.server.api.ApiContracts;
import com.example.signer.ui.ErrorMessages;
import javafx.scene.control.ButtonBase;
import com.example.signer.ui.support.Background;
import com.example.signer.exception.SignerException;
import com.example.signer.remote.SigningTaskModels.Participant;
import com.example.signer.remote.SigningTaskModels.SigningTask;
import com.example.signer.remote.SigningTaskModels.TaskFilter;
import com.example.signer.remote.SigningTaskModels.TaskSort;
import com.example.signer.remote.SigningTaskModels.TaskState;
import com.example.signer.server.api.ApiContracts.TaskItemView;

import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import javafx.animation.FadeTransition;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The "Signing Tasks" workspace. It answers, at a glance: which documents
 * need my signature, which one first, what state each is in, who asked,
 * where I am in the signing order, who is blocking, when it expires, and
 * what clicking does.
 *
 * <p>Layout: a dark navigation sidebar (real pending count) | header with
 * search and sort | clickable summary tiles (the main filters) | an
 * "action required" banner | the task list, action-required first, as
 * compact cards with the signer chain.
 *
 * <p>Pure presentation over {@link SigningTaskModels} (no business rules
 * here) and the existing {@link WorkflowApiClient}; every number and state
 * comes from the server's answer — nothing is invented.
 */
public final class SigningTasksView {

    /** Callbacks into the host dialog (opening tasks, wizard, inviter…). */
    public interface Host {
        /** Opens the task details page for one workflow. */
        void openTask(String workflowId);

        /** Opens the current document (with its signatures) for one workflow. */
        default void openDocument(String workflowId) {
            openTask(workflowId);
        }

        /** Starts the new-workflow wizard. */
        void newWorkflow();

        /** Opens the inviter (owner-side email invitations). */
        void inviteSigner();

        /** Opens the owner tracking view. */
        void manageWorkflows();

        /** Signs out (clears the cached session) and returns to sign-in. */
        void signOut();

        /** Shows the technical details dialog for one workflow. */
        void showDetails(TaskItemView item);
    }

    private final WorkflowApiClient client;
    private final String username;
    private final Host host;

    private final BorderPane root = new BorderPane();
    private final VBox listArea = new VBox(12);
    private final ScrollPane scroll = new ScrollPane(listArea);
    private final TextField searchField = new TextField();
    private final Label updatedLabel = new Label();
    private final Label navBadge = new Label();
    private final HBox tiles = new HBox(10);
    private final FlowPane moreFilters = new FlowPane(8, 6);
    private final HBox actionBanner = new HBox(12);
    private final Map<TaskFilter, VBox> tileNodes = new EnumMap<>(TaskFilter.class);

    private final ObjectProperty<List<TaskItemView>> rawItems =
            new SimpleObjectProperty<>(List.of());
    private final StringProperty query = new SimpleStringProperty("");
    private final ObjectProperty<TaskFilter> filter = new SimpleObjectProperty<>(TaskFilter.ALL);
    private final ObjectProperty<TaskSort> sort = new SimpleObjectProperty<>(TaskSort.RECENTLY_UPDATED);
    private boolean loaded;

    public SigningTasksView(WorkflowApiClient client, String username, Host host) {
        this.client = client;
        this.username = username;
        this.host = host;

        root.getStyleClass().add("st-root");
        searchField.setPromptText("Search by document, requester or signer");
        searchField.getStyleClass().add("st-search-field");
        query.bind(searchField.textProperty());

        query.addListener((o, was, is) -> render());
        filter.addListener((o, was, is) -> render());
        sort.addListener((o, was, is) -> render());
        rawItems.addListener((o, was, is) -> render());

        buildLayout();
        render();
    }

    /** The scene-graph root of this workspace. */
    public Region getRoot() {
        return root;
    }

    /** The task the list shows for {@code workflowId}, or null (details page). */
    public SigningTask summaryOf(String workflowId) {
        TaskItemView raw = findRaw(workflowId);
        return raw == null ? null : SigningTaskModels.from(raw);
    }

    /** Kicks off the initial (or refresh) load with skeleton state. */
    public void load() {
        load(null);
    }

    /**
     * Loads with skeleton state; {@code afterLoad} runs on the FX thread
     * after data arrives (used to focus a workflow from an invitation).
     */
    public void load(Runnable afterLoad) {
        showSkeleton();
        Task<List<TaskItemView>> task = new Task<>() {
            @Override
            protected List<TaskItemView> call() {
                ApiContracts.SigningTasksView payload =
                        client.signingTasks();
                return payload == null ? List.of() : payload.tasks();
            }
        };
        task.setOnSucceeded(e -> {
            loaded = true;
            rawItems.set(task.getValue());
            updatedLabel.setText("Updated " + DateTimeFormatter.ofPattern("HH:mm")
                    .withZone(ZoneId.systemDefault()).format(Instant.now()));
            if (afterLoad != null) {
                afterLoad.run();
            }
        });
        task.setOnFailed(e -> showError(task.getException()));
        Background.run("signing-tasks-load", task);
    }

    /* ------------------------------------------------------------------
     * layout
     * ------------------------------------------------------------------ */

    private void buildLayout() {
        root.setLeft(buildSidebar());

        // ---- header ------------------------------------------------------
        Label title = new Label("Signing Tasks");
        title.getStyleClass().add("st-title");
        Label subtitle = new Label("Documents that require your attention");
        subtitle.getStyleClass().add("st-subtitle");
        VBox titles = new VBox(2, title, subtitle);
        HBox.setHgrow(titles, Priority.ALWAYS);
        titles.setMinWidth(0);
        Button refresh = new Button("Refresh", new FontIcon(FontAwesomeSolid.SYNC));
        refresh.getStyleClass().add("btn-ghost");
        refresh.setTooltip(new Tooltip("Reload your signing tasks (F5)"));
        refresh.setOnAction(e -> load());
        refresh.setMinWidth(Region.USE_PREF_SIZE);
        updatedLabel.getStyleClass().add("st-updated");
        updatedLabel.setMinWidth(Region.USE_PREF_SIZE);
        HBox header = new HBox(12, titles, updatedLabel, refresh);
        header.setAlignment(Pos.CENTER_LEFT);

        // ---- search + sort ----------------------------------------------
        FontIcon searchIcon = new FontIcon(FontAwesomeSolid.SEARCH);
        searchIcon.getStyleClass().add("st-search-icon");
        Button clear = new Button("", new FontIcon(FontAwesomeSolid.TIMES));
        clear.getStyleClass().add("btn-ghost");
        clear.setTooltip(new Tooltip("Clear search"));
        clear.visibleProperty().bind(searchField.textProperty().isNotEmpty());
        clear.managedProperty().bind(clear.visibleProperty());
        clear.setOnAction(e -> searchField.clear());
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.setMaxWidth(Double.MAX_VALUE);
        HBox searchBox = new HBox(8, searchIcon, searchField, clear);
        searchBox.setAlignment(Pos.CENTER_LEFT);
        searchBox.getStyleClass().add("st-search");
        HBox.setHgrow(searchBox, Priority.ALWAYS);
        searchBox.setMaxWidth(560);

        MenuButton sortMenu = new MenuButton("Sort: " + sortLabel(sort.get()),
                new FontIcon(FontAwesomeSolid.SORT_AMOUNT_DOWN));
        sortMenu.getStyleClass().add("btn-secondary");
        sortMenu.setMinWidth(Region.USE_PREF_SIZE);
        for (TaskSort s : List.of(TaskSort.RECENTLY_UPDATED, TaskSort.NEWEST,
                TaskSort.OLDEST, TaskSort.EXPIRING_SOON)) {
            MenuItem item = new MenuItem(sortLabel(s));
            item.setOnAction(e -> {
                sort.set(s);
                sortMenu.setText("Sort: " + sortLabel(s));
            });
            sortMenu.getItems().add(item);
        }
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.SOMETIMES);
        HBox searchRow = new HBox(10, searchBox, gap, sortMenu);
        searchRow.setAlignment(Pos.CENTER_LEFT);

        // ---- summary tiles (the main filters) ---------------------------
        for (TaskFilter f : List.of(TaskFilter.NEEDS_ACTION, TaskFilter.WAITING,
                TaskFilter.COMPLETED, TaskFilter.ALL)) {
            VBox tile = tile(f);
            tileNodes.put(f, tile);
            HBox.setHgrow(tile, Priority.ALWAYS);
            tiles.getChildren().add(tile);
        }
        moreFilters.getStyleClass().add("st-more-filters");

        // ---- action banner ----------------------------------------------
        actionBanner.getStyleClass().add("st-action-banner");
        actionBanner.setAlignment(Pos.CENTER_LEFT);
        actionBanner.setVisible(false);
        actionBanner.setManaged(false);

        VBox top = new VBox(14, header, searchRow, tiles, moreFilters, actionBanner);
        top.setPadding(new Insets(22, 28, 10, 28));
        top.setMinHeight(Region.USE_PREF_SIZE);

        listArea.setPadding(new Insets(6, 28, 28, 28));
        listArea.setFillWidth(true);
        scroll.getStyleClass().add("st-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox main = new VBox(top, scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        main.getStyleClass().add("st-main");
        root.setCenter(main);

        root.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.F5) {
                e.consume();
                load();
            } else if (e.isShortcutDown() && e.getCode() == KeyCode.F) {
                e.consume();
                searchField.requestFocus();
            }
        });
    }

    /** Dark navigation: only real destinations; the badge is the real pending count. */
    private Node buildSidebar() {
        Label brand = new Label("SignFlow", new FontIcon(FontAwesomeSolid.FILE_SIGNATURE));
        brand.getStyleClass().add("st-brand");

        navBadge.getStyleClass().add("st-nav-badge");
        Node tasksNav = navItem(FontAwesomeSolid.INBOX, "Signing Tasks", navBadge, true,
                () -> filter.set(TaskFilter.ALL));
        Node completedNav = navItem(FontAwesomeSolid.CHECK_DOUBLE, "Completed", null, false,
                () -> filter.set(TaskFilter.COMPLETED));
        Node sentNav = navItem(FontAwesomeSolid.PAPER_PLANE, "Sent for signature", null, false,
                host::manageWorkflows);

        Button send = new Button("Send a document", new FontIcon(FontAwesomeSolid.PLUS));
        send.getStyleClass().addAll("btn-primary", "st-send");
        send.setMaxWidth(Double.MAX_VALUE);
        send.setTooltip(new Tooltip("Pick a PDF, add the signers and send them invitations"));
        send.setOnAction(e -> host.newWorkflow());
        Hyperlink invite = new Hyperlink("Invite a signer by email…");
        invite.getStyleClass().add("st-nav-link");
        invite.setOnAction(e -> host.inviteSigner());

        Label avatar = new Label(username == null || username.isBlank()
                ? "?" : username.substring(0, 1).toUpperCase());
        avatar.getStyleClass().add("st-avatar");
        avatar.setMinWidth(Region.USE_PREF_SIZE);
        Label name = new Label(username == null ? "" : username);
        name.getStyleClass().add("st-user");
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        Hyperlink signOut = new Hyperlink("Sign out");
        signOut.getStyleClass().add("st-nav-link");
        signOut.setOnAction(e -> host.signOut());
        VBox who = new VBox(0, name, signOut);
        who.setMinWidth(0);
        HBox profile = new HBox(10, avatar, who);
        profile.setAlignment(Pos.CENTER_LEFT);
        Tooltip.install(profile, new Tooltip("Server: " + client.baseUrl()));

        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        VBox side = new VBox(6, brand, spacer(14), tasksNav, completedNav, sentNav,
                spacer(14), send, invite, grow, profile);
        side.getStyleClass().add("st-sidebar");
        side.setPrefWidth(220);
        side.setMinWidth(200);
        return side;
    }

    private Node navItem(Ikon icon, String text, Label badge, boolean selected, Runnable action) {
        FontIcon glyph = new FontIcon(icon);
        glyph.getStyleClass().add("st-nav-icon");
        Label label = new Label(text);
        label.getStyleClass().add("st-nav-text");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox item = badge == null ? new HBox(10, glyph, label) : new HBox(10, glyph, label, gap, badge);
        item.setAlignment(Pos.CENTER_LEFT);
        item.getStyleClass().add("st-nav-item");
        if (selected) {
            item.getStyleClass().add("st-nav-item--selected");
        }
        item.setCursor(Cursor.HAND);
        item.setFocusTraversable(true);
        item.setOnMouseClicked(e -> action.run());
        item.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                action.run();
            }
        });
        return item;
    }

    /** A clickable summary tile: label + real count. */
    private VBox tile(TaskFilter f) {
        Label label = new Label(tileLabel(f));
        label.getStyleClass().add("st-tile-label");
        Label count = new Label("–");
        count.getStyleClass().add("st-tile-count");
        Label unit = new Label("documents");
        unit.getStyleClass().add("st-tile-unit");
        HBox numbers = new HBox(6, count, unit);
        numbers.setAlignment(Pos.BASELINE_LEFT);
        VBox tile = new VBox(2, label, numbers);
        tile.getStyleClass().addAll("st-tile", "st-tile--" + f.name().toLowerCase());
        tile.setCursor(Cursor.HAND);
        tile.setFocusTraversable(true);
        tile.setMinWidth(130);
        tile.setOnMouseClicked(e -> filter.set(f));
        tile.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                filter.set(f);
            }
        });
        tile.setUserData(new Label[]{count, unit});
        return tile;
    }

    /* ------------------------------------------------------------------
     * rendering
     * ------------------------------------------------------------------ */

    private void render() {
        List<SigningTask> all = SigningTaskModels.fromAll(rawItems.get());
        List<SigningTask> shown = SigningTaskModels.sort(
                SigningTaskModels.filter(SigningTaskModels.search(all, query.get()),
                        filter.get()),
                sort.get());
        int needsAction = SigningTaskModels.count(all, TaskFilter.NEEDS_ACTION);

        // sidebar badge: only when there is something to do
        navBadge.setText(String.valueOf(needsAction));
        navBadge.setVisible(loaded && needsAction > 0);
        navBadge.setManaged(navBadge.isVisible());

        // tiles
        for (var entry : tileNodes.entrySet()) {
            Label[] parts = (Label[]) entry.getValue().getUserData();
            int n = SigningTaskModels.count(all, entry.getKey());
            parts[0].setText(loaded ? String.valueOf(n) : "–");
            parts[1].setText(n == 1 ? "document" : "documents");
            entry.getValue().getStyleClass().remove("st-tile--selected");
            if (entry.getKey() == filter.get()) {
                entry.getValue().getStyleClass().add("st-tile--selected");
            }
        }
        // rarer states as small filters, only when they occur
        moreFilters.getChildren().clear();
        for (TaskFilter f : List.of(TaskFilter.EXPIRED, TaskFilter.DECLINED, TaskFilter.REVOKED)) {
            int n = SigningTaskModels.count(all, f);
            if (n > 0 || filter.get() == f) {
                Hyperlink link = new Hyperlink(tileLabel(f) + " (" + n + ")");
                link.getStyleClass().add("st-more-filter");
                if (filter.get() == f) {
                    link.getStyleClass().add("st-more-filter--selected");
                }
                link.setOnAction(e -> filter.set(f));
                moreFilters.getChildren().add(link);
            }
        }
        if (!moreFilters.getChildren().isEmpty()) {
            Label lead = new Label("Also:");
            lead.getStyleClass().add("st-updated");
            moreFilters.getChildren().add(0, lead);
        }
        moreFilters.setVisible(!moreFilters.getChildren().isEmpty());
        moreFilters.setManaged(moreFilters.isVisible());

        updateActionBanner(all, needsAction);

        // list: action required first, then everything else
        VBox list = new VBox(10);
        List<SigningTask> action = shown.stream()
                .filter(t -> t.state() == TaskState.ACTION_REQUIRED).toList();
        List<SigningTask> others = shown.stream()
                .filter(t -> t.state() != TaskState.ACTION_REQUIRED).toList();
        if (!action.isEmpty()) {
            list.getChildren().add(sectionHeader("Action required", action.size()));
            action.forEach(t -> list.getChildren().add(card(t)));
        }
        if (!others.isEmpty()) {
            list.getChildren().add(sectionHeader(action.isEmpty() ? sectionTitle()
                    : "Other documents", others.size()));
            others.forEach(t -> list.getChildren().add(card(t)));
        }
        if (!loaded) {
            return; // the skeleton stays until the first answer
        }
        if (list.getChildren().isEmpty()) {
            list.getChildren().add(emptyState(all.isEmpty()));
        }
        listArea.getChildren().setAll(list);
        FadeTransition fade = new FadeTransition(Duration.millis(140), list);
        fade.setFromValue(0.4);
        fade.setToValue(1);
        fade.play();
    }

    private String sectionTitle() {
        return switch (filter.get()) {
            case WAITING -> "Waiting for others";
            case COMPLETED -> "Completed";
            case EXPIRED -> "Expired";
            case DECLINED -> "Declined";
            case REVOKED -> "Cancelled";
            default -> "Documents";
        };
    }

    private void updateActionBanner(List<SigningTask> all, int needsAction) {
        SigningTask first = SigningTaskModels.nextToSign(all);
        boolean show = loaded && first != null;
        actionBanner.setVisible(show);
        actionBanner.setManaged(show);
        if (!show) {
            return;
        }
        FontIcon icon = new FontIcon(FontAwesomeSolid.PEN_NIB);
        icon.getStyleClass().add("st-banner-icon");
        Label text = new Label(needsAction == 1
                ? "You have 1 document waiting for your signature."
                : "You have " + needsAction + " documents waiting for your signature.");
        text.getStyleClass().add("st-banner-text");
        text.setWrapText(true);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button go = new Button(needsAction == 1 ? "Review it" : "Review the first task",
                new FontIcon(FontAwesomeSolid.ARROW_RIGHT));
        go.setContentDisplay(ContentDisplay.RIGHT);
        go.getStyleClass().add("btn-primary");
        go.setMinWidth(Region.USE_PREF_SIZE);
        go.setTooltip(new Tooltip(first.title()));
        go.setOnAction(e -> host.openTask(first.workflowId()));
        actionBanner.getChildren().setAll(icon, text, go);
    }

    private Node sectionHeader(String text, int count) {
        Label label = new Label(text.toUpperCase());
        label.getStyleClass().add("st-section");
        Label n = new Label(String.valueOf(count));
        n.getStyleClass().add("st-section-count");
        HBox box = new HBox(8, label, n);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(8, 0, 0, 2));
        return box;
    }

    /**
     * One task card. Hierarchy: state → document → what is needed →
     * requester → signing order → expiry → the one primary action.
     */
    private Node card(SigningTask t) {
        TaskState state = t.state();

        Region stripe = new Region();
        stripe.getStyleClass().addAll("st-stripe", "st-stripe--" + state.style());
        stripe.setMinWidth(4);
        stripe.setMaxHeight(Double.MAX_VALUE);

        // 1. state + expiry
        Label chip = new Label(state.label().toUpperCase(), new FontIcon(stateIcon(state)));
        chip.getStyleClass().addAll("st-state", "st-state--" + state.style());
        chip.setMinWidth(Region.USE_PREF_SIZE);
        HBox row1 = new HBox(10, chip);
        row1.setAlignment(Pos.CENTER_LEFT);
        if (t.expiresText() != null
                && (state == TaskState.ACTION_REQUIRED || state == TaskState.EXPIRED
                        || state == TaskState.WAITING)) {
            Label expires = new Label(t.expiresText(), new FontIcon(FontAwesomeSolid.CLOCK));
            expires.getStyleClass().add(t.expiresSoon() ? "st-expires-soon" : "st-meta");
            if (t.expiresSoon()) {
                expires.setTooltip(new Tooltip("Expires within 48 hours"));
            }
            row1.getChildren().add(expires);
        }

        // 2. document
        Label title = new Label(t.title());
        title.getStyleClass().add("st-doc-title");
        title.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        title.setTooltip(new Tooltip(t.title()));
        title.setMinWidth(0);

        // 3. what is needed / who we wait for
        Label explain = new Label(t.explanation()
                + (t.yourRole() != null && state == TaskState.ACTION_REQUIRED
                ? "  ·  " + t.yourRole() : ""));
        explain.getStyleClass().add(state == TaskState.ACTION_REQUIRED
                ? "st-explain-strong" : "st-explain");
        explain.setWrapText(true);
        explain.setMinWidth(0);

        // 4. requester + dates
        String requester = t.senderName() == null ? "Requester unknown"
                : "Requested by " + t.senderName();
        String created = t.dateText() == null ? "" : "  ·  " + t.dateKind() + " " + t.dateText();
        Label meta = new Label(requester + created);
        meta.getStyleClass().add("st-meta");
        meta.setMinWidth(0);
        meta.setTextOverrun(OverrunStyle.ELLIPSIS);

        VBox text = new VBox(4, row1, title, explain, meta);
        // 5. signing order
        if (!t.participants().isEmpty()) {
            text.getChildren().add(signerChain(t));
        }
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);

        // 6. actions
        Button primary = new Button(t.primaryAction(), new FontIcon(primaryIcon(t)));
        primary.getStyleClass().add(state == TaskState.ACTION_REQUIRED ? "btn-primary" : "btn-secondary");
        primary.setMinWidth(Region.USE_PREF_SIZE);
        primary.setOnAction(e -> primaryAction(t));
        VBox actions = new VBox(4, primary);
        actions.setAlignment(Pos.CENTER_RIGHT);
        if (state == TaskState.COMPLETED) {
            Hyperlink workflow = new Hyperlink("View Workflow");
            workflow.setOnAction(e -> host.openTask(t.workflowId()));
            actions.getChildren().add(workflow);
        }
        Hyperlink details = new Hyperlink("Technical details");
        details.getStyleClass().add("st-tech-link");
        details.setOnAction(e -> host.showDetails(findRaw(t.workflowId())));
        actions.getChildren().add(details);
        actions.setMinWidth(Region.USE_PREF_SIZE);

        HBox card = new HBox(16, stripe, text, actions);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().addAll("st-card", "st-card--" + state.style());
        card.setCursor(Cursor.HAND);
        card.setFocusTraversable(true);
        card.setOnMouseClicked(e -> {
            if (!insideControl(e.getPickResult().getIntersectedNode())) {
                host.openTask(t.workflowId());
            }
        });
        card.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                primaryAction(t);
            }
        });
        return card;
    }

    private void primaryAction(SigningTask t) {
        if (t.state() == TaskState.COMPLETED) {
            host.openDocument(t.workflowId());
        } else {
            host.openTask(t.workflowId());
        }
    }

    /**
     * ✓ Alice → ● You → ○ Charlie for ordered signing; "Any order:" with
     * no arrows for flexible workflows (never implies an order the
     * workflow does not have).
     */
    private Node signerChain(SigningTask t) {
        FlowPane chain = new FlowPane(6, 4);
        chain.getStyleClass().add("st-chain");
        if (t.anyOrder()) {
            Label any = new Label("Any order:");
            any.getStyleClass().add("st-chain-mode");
            any.setTooltip(new Tooltip("Signers can sign in any order, one at a time"));
            chain.getChildren().add(any);
        }
        List<Participant> people = t.participants();
        for (int i = 0; i < people.size(); i++) {
            Participant p = people.get(i);
            boolean mine = p.you();
            String kind = p.signed() ? "signed"
                    : p.current() || mine && t.state() == TaskState.ACTION_REQUIRED ? "current"
                    : "waiting";
            FontIcon dot = new FontIcon(switch (kind) {
                case "signed" -> FontAwesomeSolid.CHECK_CIRCLE;
                case "current" -> FontAwesomeSolid.DOT_CIRCLE;
                default -> FontAwesomeSolid.CIRCLE;
            });
            dot.getStyleClass().add("st-chain-dot--" + kind);
            Label person = new Label(mine ? "You" : p.name(), dot);
            person.getStyleClass().addAll("st-chain-person", "st-chain-person--" + kind);
            person.setMaxWidth(160);
            person.setTextOverrun(OverrunStyle.ELLIPSIS);
            person.setTooltip(new Tooltip((mine ? "You (" + p.name() + ")" : p.name())
                    + " — " + switch (kind) {
                        case "signed" -> "signed";
                        case "current" -> mine ? "your signature is required" : "signing now";
                        default -> "waiting";
                    }));
            chain.getChildren().add(person);
            if (!t.anyOrder() && i < people.size() - 1) {
                FontIcon arrow = new FontIcon(FontAwesomeSolid.LONG_ARROW_ALT_RIGHT);
                arrow.getStyleClass().add("st-chain-arrow");
                chain.getChildren().add(arrow);
            }
        }
        return chain;
    }

    private static Ikon stateIcon(TaskState state) {
        return switch (state) {
            case ACTION_REQUIRED -> FontAwesomeSolid.PEN_NIB;
            case WAITING -> FontAwesomeSolid.HOURGLASS_HALF;
            case COMPLETED -> FontAwesomeSolid.CHECK_CIRCLE;
            case EXPIRED -> FontAwesomeSolid.CLOCK;
            case DECLINED -> FontAwesomeSolid.TIMES_CIRCLE;
            case CANCELLED -> FontAwesomeSolid.BAN;
            case ERROR -> FontAwesomeSolid.EXCLAMATION_TRIANGLE;
        };
    }

    private static Ikon primaryIcon(SigningTask t) {
        return switch (t.state()) {
            case ACTION_REQUIRED -> t.certificatePending()
                    ? FontAwesomeSolid.ID_CARD : FontAwesomeSolid.ARROW_RIGHT;
            case COMPLETED -> FontAwesomeSolid.FILE_PDF;
            case WAITING -> FontAwesomeSolid.STREAM;
            default -> FontAwesomeSolid.INFO_CIRCLE;
        };
    }

    private static boolean insideControl(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n instanceof ButtonBase) {
                return true;
            }
        }
        return false;
    }

    /** Finds the raw backend item for the Details dialog. */
    private TaskItemView findRaw(String workflowId) {
        return rawItems.get().stream()
                .filter(i -> workflowId.equals(i.workflowId()))
                .findFirst().orElse(null);
    }

    /* ------------------------------------------------------------------
     * states: skeleton / empty / error
     * ------------------------------------------------------------------ */

    private void showSkeleton() {
        VBox list = new VBox(10);
        for (int i = 0; i < 3; i++) {
            VBox lines = new VBox(8);
            for (int w : new int[]{110, 260, 340, 200}) {
                Region line = new Region();
                line.getStyleClass().add("skeleton-line");
                line.setPrefWidth(w);
                line.setMaxWidth(w);
                lines.getChildren().add(line);
            }
            HBox skel = new HBox(16, lines);
            skel.getStyleClass().add("st-card");
            list.getChildren().add(skel);
        }
        listArea.getChildren().setAll(list);
    }

    private Node emptyState(boolean nothingAtAll) {
        VBox empty = new VBox(10);
        empty.getStyleClass().add("st-empty");
        empty.setAlignment(Pos.CENTER);
        FontIcon icon = new FontIcon(FontAwesomeSolid.CHECK_CIRCLE);
        icon.getStyleClass().add("st-empty-icon");
        boolean narrowed = filter.get() != TaskFilter.ALL || !query.get().isBlank();
        boolean caughtUp = filter.get() == TaskFilter.NEEDS_ACTION && query.get().isBlank();
        Label title = new Label(nothingAtAll ? "No signing tasks"
                : caughtUp ? "All caught up" : "No documents match");
        title.getStyleClass().add("st-empty-title");
        Label hint = new Label(nothingAtAll
                ? "You don't have any documents waiting for your signature."
                : caughtUp ? "Nothing needs your signature right now."
                : "Try a different search, or show all documents.");
        hint.getStyleClass().add("st-empty-hint");
        hint.setWrapText(true);
        empty.getChildren().addAll(icon, title, hint);
        if (narrowed && !nothingAtAll) {
            Button all = new Button("Show all documents");
            all.getStyleClass().add("btn-secondary");
            all.setOnAction(e -> {
                searchField.clear();
                filter.set(TaskFilter.ALL);
            });
            empty.getChildren().add(all);
        } else if (nothingAtAll) {
            Button send = new Button("Send a document for signature",
                    new FontIcon(FontAwesomeSolid.PAPER_PLANE));
            send.getStyleClass().add("btn-secondary");
            send.setOnAction(e -> host.newWorkflow());
            empty.getChildren().add(send);
        }
        return empty;
    }

    /** Recoverable error: what happened, what to do, one action. No raw exceptions. */
    private void showError(Throwable t) {
        boolean network = t instanceof SignerException se && WorkflowApiClient.isTransportFailure(se);
        VBox err = new VBox(10);
        err.getStyleClass().add("st-empty");
        err.setAlignment(Pos.CENTER);
        FontIcon icon = new FontIcon(network ? FontAwesomeSolid.WIFI : FontAwesomeSolid.EXCLAMATION_TRIANGLE);
        icon.getStyleClass().add("st-error-icon");
        Label title = new Label(network
                ? "SignFlow couldn't connect to the coordination server."
                : "Your signing tasks could not be loaded.");
        title.getStyleClass().add("st-empty-title");
        title.setWrapText(true);
        Label hint = new Label(network
                ? "Check your connection, or ask your administrator whether the server is"
                        + " running. Then try again."
                : ErrorMessages.generic(t));
        hint.getStyleClass().add("st-empty-hint");
        hint.setWrapText(true);
        Button retry = new Button("Try again", new FontIcon(FontAwesomeSolid.SYNC));
        retry.getStyleClass().add("btn-primary");
        retry.setOnAction(e -> load());
        err.getChildren().addAll(icon, title, hint, retry);
        listArea.getChildren().setAll(err);
    }

    /* ------------------------------------------------------------------
     * plumbing
     * ------------------------------------------------------------------ */

    private static Region spacer(double h) {
        Region r = new Region();
        r.setMinHeight(h);
        return r;
    }

    private static String tileLabel(TaskFilter f) {
        return switch (f) {
            case ALL -> "All tasks";
            case NEEDS_ACTION -> "Action required";
            case WAITING -> "Waiting";
            case COMPLETED -> "Completed";
            case DECLINED -> "Declined";
            case EXPIRED -> "Expired";
            case REVOKED -> "Cancelled";
        };
    }

    private static String sortLabel(TaskSort s) {
        return switch (s) {
            case NEEDS_ACTION_FIRST -> "Action required first";
            case RECENTLY_UPDATED -> "Recently updated";
            case NEWEST -> "Newest";
            case OLDEST -> "Oldest";
            case EXPIRING_SOON -> "Expiring soon";
        };
    }

    /** One plain sentence about what happens next (kept for other callers). */
    static String nextStepText(SigningTask t) {
        return t.explanation();
    }
}
