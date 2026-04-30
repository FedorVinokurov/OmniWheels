#include <Servo.h>

// Servo test for Arduino Uno + L293D/Adafruit Motor Shield v1.
// SERVO_1 on this shield is connected to Arduino pin D10.

const int SERVO1_PIN = 10;
const int LEFT_ANGLE = 30;
const int RIGHT_ANGLE = 150;
const int CENTER_ANGLE = 90;
const int STEP_DELAY_MS = 20;
const int EDGE_PAUSE_MS = 500;

Servo servo1;

void setup() {
  servo1.attach(SERVO1_PIN);
  servo1.write(CENTER_ANGLE);
  delay(1000);
}

void loop() {
  for (int angle = LEFT_ANGLE; angle <= RIGHT_ANGLE; angle++) {
    servo1.write(angle);
    delay(STEP_DELAY_MS);
  }
  delay(EDGE_PAUSE_MS);

  for (int angle = RIGHT_ANGLE; angle >= LEFT_ANGLE; angle--) {
    servo1.write(angle);
    delay(STEP_DELAY_MS);
  }
  delay(EDGE_PAUSE_MS);
}
