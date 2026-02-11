import SwiftUI

struct OnboardingView: View {
    let onFinish: () -> Void

    @State private var currentPage: Int = 0

    private let pages: [OnboardingPage] = [
        OnboardingPage(
            symbol: "sparkles",
            title: "AI Assistant",
            description: "ClawPhones is your built-in AI assistant for everyday tasks, work, and creativity."
        ),
        OnboardingPage(
            symbol: "square.stack.3d.up",
            title: "Multiple AI Models",
            description: "Switch between DeepSeek, Kimi, and Claude to get the best answer for each question."
        ),
        OnboardingPage(
            symbol: "lock.shield",
            title: "Your Data, Your Device",
            description: "Privacy-first by design. Your chats and settings stay on your device whenever possible."
        ),
        OnboardingPage(
            symbol: "arrow.forward.circle.fill",
            title: "Get Started",
            description: "Create an account or log in to start chatting in seconds."
        )
    ]

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Spacer()
                Button("Skip") {
                    onFinish()
                }
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(.white.opacity(0.85))
            }
            .padding(.horizontal, 22)
            .padding(.top, 16)

            TabView(selection: $currentPage) {
                ForEach(Array(pages.enumerated()), id: \.offset) { index, page in
                    VStack(spacing: 20) {
                        Image(systemName: page.symbol)
                            .font(.system(size: 64, weight: .semibold))
                            .foregroundStyle(Color(red: 1.0, green: 0.78, blue: 0.36))
                            .padding(.bottom, 8)

                        Text(page.title)
                            .font(.system(size: 34, weight: .bold, design: .rounded))
                            .multilineTextAlignment(.center)
                            .foregroundStyle(.white)

                        Text(page.description)
                            .font(.system(size: 17, weight: .regular))
                            .multilineTextAlignment(.center)
                            .foregroundStyle(.white.opacity(0.82))
                            .padding(.horizontal, 28)
                    }
                    .tag(index)
                    .padding(.horizontal, 16)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .always))
            .indexViewStyle(.page(backgroundDisplayMode: .always))

            Button {
                if currentPage >= pages.count - 1 {
                    onFinish()
                } else {
                    withAnimation(.easeInOut(duration: 0.25)) {
                        currentPage += 1
                    }
                }
            } label: {
                Text(currentPage == pages.count - 1 ? "Get Started" : "Next")
                    .font(.system(size: 18, weight: .semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .foregroundStyle(Color(red: 0.07, green: 0.11, blue: 0.22))
                    .background(Color(red: 1.0, green: 0.78, blue: 0.36))
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .padding(.horizontal, 24)
            .padding(.top, 8)
            .padding(.bottom, 30)
        }
        .background(
            LinearGradient(
                colors: [
                    Color(red: 0.06, green: 0.1, blue: 0.2),
                    Color(red: 0.12, green: 0.2, blue: 0.42)
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()
        )
    }
}

private struct OnboardingPage {
    let symbol: String
    let title: String
    let description: String
}
