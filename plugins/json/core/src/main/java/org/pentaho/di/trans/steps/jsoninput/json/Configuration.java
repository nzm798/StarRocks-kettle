/*! ******************************************************************************
 *
 * Pentaho Data Integration
 *
 * Copyright (C) 2018-2022 by Hitachi Vantara : http://www.pentaho.com
 *
 *******************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ******************************************************************************/

package org.pentaho.di.trans.steps.jsoninput.json;

/**
 * Configuration options for JsonSampler
 *
 * Created by bmorrise on 7/27/18.
 */
public class Configuration {

  private static final int DEFAULT_LINES = 100;
  private int lines = DEFAULT_LINES;
  private boolean dedupe = true;

  public int getLines() {
    return lines;
  }

  public void setLines( int lines ) {
    this.lines = lines;
  }

  public boolean isDedupe() {
    return dedupe;
  }

  public void setDedupe( boolean dedupe ) {
    this.dedupe = dedupe;
  }
}
