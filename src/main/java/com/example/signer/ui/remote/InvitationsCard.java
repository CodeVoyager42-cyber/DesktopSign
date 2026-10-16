package com.example.signer.ui.remote;

import com.example.signer.server.api.ApiContracts.InvitationLinkInfo;
import com.example.signer.server.api.ApiContracts.InvitationLinkView;
import com.example.signer.server.api.ApiContracts.InvitationStatusView;
import com.example.signer.server.api.ApiStates.EmailStatus;
import com.example.signer.server.api.ApiStates.InvitationStatus;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The workflow owner's "Invitations" card: one row per signer slot with the
 * invited address, the invitation's state, the email delivery state, when it
 * was sent or accepted, and a Resend action where it makes sense.
 *
 * <p>Only builds the view from data already loaded; the network calls stay
 * with the caller ({@link Resender}).
 */
final class InvitationsCard {

    /** Sends a fresh invitation; calls {@code onSent} on the FX thread with the new link. */
    @FunctionalInterface
    interface Resender {
        void resend(InvitationStatusView invitation, Consumer<InvitationLinkView> onSent);
    }

    private InvitationsCard() {
    }

    /** @param linkInfo where links are sent from; null when the server does not say */
    static VBox build(List<InvitationStatusView> invitations, InvitationLinkInfo linkInfo,
                      Resender resender) {
        Label title = new Label("Invitations");
        title.getStyleClass().add("section-title");
        VBox card = new VBox(12, title);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(14));
        if (linkInfo != null) {
            Label where = new Label(linkInfo.localOnly()
                    ? "Links are sent from " + linkInfo.baseUrl() + ", a local address — a"
                            + " signer on another computer cannot open them. Set"
                            + " SIGNER_PUBLIC_BASE_URL on the server to fix it."
                    : "Links are sent from " + linkInfo.baseUrl() + ".");
            where.setWrapText(true);
            where.getStyleClass().add("page-subtitle");
            card.getChildren().add(where);
        }
        for (InvitationStatusView invitation : invitations) {
            card.getChildren().add(slotRow(invitation, resender));
        }
        return card;
    }

    private static VBox slotRow(InvitationStatusView inv, Resender resender) {
        Label order = new Label(String.valueOf(inv.signerOrder()));
        order.getStyleClass().add("signer-order");
        Label email = new Label(inv.email());
        email.getStyleClass().add("timeline-name");

        // SIGNED comes from the signature record itself; the invitation's own
        // state is shown as what it is (sent / accepted / expired / revoked).
        InvitationStatus status = inv.invitationStatus();
        String statusText = inv.status();
        String statusStyle = badgeClassFor(status);
        if (inv.slotSigned()) {
            statusText = "Signed";
            statusStyle = "badge--signed";
        } else if (status == InvitationStatus.ACCEPTED) {
            statusText = "Accepted — not signed";
        }
        Label badge = new Label(statusText);
        badge.getStyleClass().addAll("badge", statusStyle);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox head = new HBox(10, order, email, gap, badge);
        head.setAlignment(Pos.CENTER_LEFT);
        if (inv.emailStatus() != null) {
            Label mail = new Label("email: " + inv.emailStatus().toLowerCase(Locale.ROOT));
            mail.getStyleClass().addAll("badge",
                    inv.emailState() == EmailStatus.FAILED ? "badge--bad" : "badge--waiting");
            mail.setTooltip(new Tooltip(
                    "FAILED means the email could not be delivered — use Resend to try again"));
            head.getChildren().add(2, mail);
        }

        Label stamp = new Label(inv.acceptedAt() != null ? "Accepted " + shortDate(inv.acceptedAt())
                : inv.createdAt() != null ? "Sent " + shortDate(inv.createdAt())
                : "");
        stamp.getStyleClass().add("page-subtitle");

        VBox slot = new VBox(4, head, stamp);
        if (status == InvitationStatus.PENDING || status == InvitationStatus.EXPIRED) {
            Button resend = new Button("Resend invitation", new FontIcon(FontAwesomeSolid.ENVELOPE));
            resend.getStyleClass().add("btn-secondary");
            resend.setTooltip(new Tooltip("Invalidate the emailed link and send a fresh one"));
            HBox resendRow = new HBox(10, resend);
            resend.setOnAction(e -> resender.resend(inv, sent -> {
                // Show the new link in place: the server returns it once and
                // keeps only its hash, so a refresh would lose it.
                slot.getChildren().remove(resendRow);
                if (sent != null) {
                    slot.getChildren().add(freshLinkRow(sent));
                }
            }));
            slot.getChildren().add(resendRow);
        }
        return slot;
    }

    /** The just-emailed link, with a copy action. */
    private static HBox freshLinkRow(InvitationLinkView sent) {
        Label url = new Label(NewWorkflowWizard.shortenLink(sent.invitationUrl()));
        url.getStyleClass().addAll("page-subtitle", "mono-text");
        url.setTooltip(new Tooltip(sent.invitationUrl()));
        Button copy = new Button("Copy link", new FontIcon(FontAwesomeSolid.COPY));
        copy.getStyleClass().add("btn-ghost");
        copy.setOnAction(e -> {
            NewWorkflowWizard.copyToClipboard(sent.invitationUrl());
            copy.setText("Copied");
        });
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox row = new HBox(10, url, gap, copy);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * Badge style per invitation state. ACCEPTED deliberately does NOT look
     * signed: opening an invitation is not signing.
     */
    static String badgeClassFor(InvitationStatus status) {
        return switch (status) {
            case COMPLETED -> "badge--signed";
            case EXPIRED -> "badge--expired";
            case REVOKED -> "badge--revoked";
            default -> "badge--waiting";
        };
    }

    /** "Mar 4, 17:20" from an ISO-8601 instant; the raw text if it can't be read. */
    static String shortDate(String isoInstant) {
        try {
            return DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ENGLISH)
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.parse(isoInstant));
        } catch (Exception e) {
            return isoInstant;
        }
    }
}
