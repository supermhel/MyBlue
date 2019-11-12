/*
  AnalogReadSerial

  Reads an analog input on pin 0, 2 and 4, prints the result to the Serial Monitor.

*/
#include <ArduinoJson.h> //Json Library
#define BUFFER_SIZE 255
#define UPDATE_DELAY 500 //time in ms

// the setup routine runs once when you press reset:
void setup() {
  // initialize serial communication at 9600 bits per second:
  Serial.begin(9600); // this is the correct baudrate to send data over BLE
    
}



// the loop routine runs over and over again forever:
void loop() {
 
  // read the input on analog pin 0:
  int sensorValue = analogRead(A0);
  int sensorVal2  = analogRead(A2);
  int sensorVal3  = analogRead(A4);
  
 //transforming into Json format
  /*StaticJsonDocument<200> doc;
  JsonObject root = doc.to<JsonObject>();
  root["sensor_1"] = sensorValue;
  root["sensor_2"] = sensorVal2;
  root["sensor_3"] = sensorVal3;*/

  // print out the value you read:
  char str[BUFFER_SIZE];

  snprintf(str, sizeof(char)*BUFFER_SIZE, "{\"Sensor_1\": %d,\"Sensor_2\": %d,\"Sensor_3\": %d}", sensorValue, sensorVal2, sensorVal3);

  Serial.println(str);
  //serializeJson(doc,Serial);
  //Serial.println();
  delay(UPDATE_DELAY);        // delay in between reads for stability

 
}
