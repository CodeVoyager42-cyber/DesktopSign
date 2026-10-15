package com.example.signer.ui.support;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Remembers the sequence of UI components the user visited so that a Back
 * button can return to the previously visited component instead of a fixed
 * destination.
 *
 * <p>The stack always carries the current component on top. Call
 * {@link #push(Object)} whenever a component is shown; {@link #back()}
 * removes the current component and returns the one visited before it, or
 * {@code null} when the current component is the first of the session.
 * Consecutive duplicates collapse, so re-showing the same screen (e.g. after
 * a dialog on top of it closed) does not grow the stack.</p>
 *
 * <p>Not a UI class: plain and unit-testable.</p>
 */
public final class NavigationHistory<T> {

    private final Deque<T> stack = new ArrayDeque<>();

    /** Records a navigation to {@code screen}; consecutive duplicates collapse. */
    public void push(T screen) {
        if (screen == null || screen.equals(stack.peek())) {
            return;
        }
        stack.push(screen);
    }

    /**
     * Navigates one step back: drops the current component.
     *
     * @return the previously visited component, or {@code null} when there is
     *         none (the current component is the first of the session)
     */
    public T back() {
        if (stack.size() < 2) {
            return null;
        }
        stack.pop();
        return stack.peek();
    }

    /**
     * Removes the current component without navigating — used when a component
     * on top of the history (e.g. a dialog) is left without a replacement.
     */
    public void pop() {
        stack.poll();
    }

    /** The current component, or {@code null} when nothing was visited yet. */
    public T current() {
        return stack.peek();
    }

    /** Forgets all history (used e.g. after sign-out starts a new session). */
    public void clear() {
        stack.clear();
    }
}
