package com.poolapp;

import com.poolapp.ui.PoolAppFrame;

import javax.swing.SwingUtilities;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            PoolAppFrame frame = new PoolAppFrame();
            frame.setVisible(true);
        });
    }
}
