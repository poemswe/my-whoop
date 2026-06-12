import SwiftUI

// MARK: - RecoveryRing
// Circular progress ring showing a recovery percentage.
// Ring stroke is colored by the recovery band (green/yellow/red).
// The integer % is rendered large+bold in the center with a caption label below.

struct RecoveryRing: View {

    /// Recovery percentage 0–100
    var percent: Double
    var size: CGFloat = 180
    var strokeWidth: CGFloat = 14

    /// Drives both the arc trim and the centre count-up. Starts at 0 and sweeps to
    /// `clamped` on appear (WHOOP's signature ring-fill), then tracks data changes.
    @State private var shown: Double = 0

    // Clamp to valid range
    private var clamped: Double { min(100, max(0, percent)) }
    private var progress: Double { shown / 100 }
    private var bandColor: Color { WH.Color.recoveryColor(forPercent: clamped) }

    var body: some View {
        ZStack {
            // --- Track (faint ring) ---
            Circle()
                .stroke(WH.Color.ringTrack, lineWidth: strokeWidth)

            // --- Filled arc ---
            Circle()
                .trim(from: 0, to: progress)
                .stroke(
                    bandColor,
                    style: StrokeStyle(
                        lineWidth: strokeWidth,
                        lineCap: .round
                    )
                )
                .rotationEffect(.degrees(-90))

            // --- Glow effect (subtle) ---
            Circle()
                .trim(from: 0, to: progress)
                .stroke(
                    bandColor.opacity(0.25),
                    style: StrokeStyle(lineWidth: strokeWidth + 8, lineCap: .round)
                )
                .rotationEffect(.degrees(-90))
                .blur(radius: 6)

            // --- Center content ---
            VStack(spacing: 2) {
                CountingNumber(value: shown, fontSize: size * 0.32)

                Text("RECOVERY")
                    .font(WH.Font.cardTitle)
                    .foregroundStyle(WH.Color.textSecondary)
                    .tracking(1.5)
            }
        }
        .frame(width: size, height: size)
        .onAppear {
            withAnimation(.easeOut(duration: 0.9)) { shown = clamped }
        }
        .onChange(of: clamped) { newValue in
            withAnimation(.easeOut(duration: 0.6)) { shown = newValue }
        }
    }
}

// MARK: - Counting number
// An Animatable wrapper so the centre integer interpolates frame-by-frame during the
// ring's count-up (a plain `Text("\(Int(value))")` would snap instead of counting).

private struct CountingNumber: View, Animatable {
    var value: Double
    var fontSize: CGFloat

    var animatableData: Double {
        get { value }
        set { value = newValue }
    }

    var body: some View {
        Text("\(Int(value.rounded()))")
            .font(WH.Font.metricHero(size: fontSize))
            .foregroundStyle(WH.Color.textPrimary)
            .monospacedDigit()
    }
}

// MARK: - Preview

#Preview("Recovery Ring — all bands") {
    HStack(spacing: WH.Spacing.xl) {
        RecoveryRing(percent: 82, size: 140)
        RecoveryRing(percent: 51, size: 140)
        RecoveryRing(percent: 18, size: 140)
    }
    .padding(WH.Spacing.xl)
    .background(WH.Color.background)
}
