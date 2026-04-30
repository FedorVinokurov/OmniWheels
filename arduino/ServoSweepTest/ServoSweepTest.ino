#include <Servo.h>

// Simple endless test for Motor Shield v1 SERVO_1.
// SERVO_1 on Adafruit/L293D Motor Shield v1 is Arduino D10.

const int SERVO1_PIN = 10;
const int LEFT_ANGLE = 30;
const int RIGHT_ANGLE = 150;
const int CENTER_ANGLE = 90;
const int STEP_DELAY_MS = 15;
const int END_PAUSE_MS = 500;

Servo servo1;

void setup() {
  Serial.begin(115200);
  servo1.attach(SERVO1_PIN);
  servo1.write(CENTER_ANGLE);
  delay(1000);
}

void loop() {
  Serial.println("servo left");
  sweepServo(CENTER_ANGLE, LEFT_ANGLE);
  delay(END_PAUSE_MS);

  Serial.println("servo right");
  sweepServo(LEFT_ANGLE, RIGHT_ANGLE);
  delay(END_PAUSE_MS);

  Serial.println("servo left");
  sweepServo(RIGHT_ANGLE, LEFT_ANGLE);
  delay(END_PAUSE_MS);

  Serial.println("servo right");
  sweepServo(LEFT_ANGLE, RIGHT_ANGLE);
  delay(END_PAUSE_MS);
}

void sweepServo(int fromAngle, int toAngle) {
  if (fromAngle < toAngle) {
    for (int angle = fromAngle; angle <= toAngle; angle++) {
      servo1.write(angle);
      delay(STEP_DELAY_MS);
    }
  } else {
    for (int angle = fromAngle; angle >= toAngle; angle--) {
      servo1.write(angle);
      delay(STEP_DELAY_MS);
    }
  }
}
