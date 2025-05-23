/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.convert2.interop;

import com.typesafe.config.Config;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.locationtech.geomesa.convert2.SimpleFeatureConverter;
import org.locationtech.geomesa.convert2.SimpleFeatureConverter$;

public class SimpleFeatureConverterLoader {
    public static SimpleFeatureConverter load(SimpleFeatureType sft, Config config) {
        return SimpleFeatureConverter$.MODULE$.apply(sft, config);
    }
}
