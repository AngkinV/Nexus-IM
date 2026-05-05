import 'package:flutter_test/flutter_test.dart';
import 'package:nexus_chat_app/app.dart';

void main() {
  testWidgets('Splash page smoke test', (WidgetTester tester) async {
    // Build our app and trigger a frame.
    await tester.pumpWidget(const NexusChatApp());

    // Verify that the app title is displayed
    expect(find.text('Nexus'), findsOneWidget);

    // Verify that the subtitle is displayed
    expect(find.text('连接未来，触手可及'), findsOneWidget);
  });
}
