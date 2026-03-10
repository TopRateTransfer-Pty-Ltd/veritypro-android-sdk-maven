/**
 * Unit tests for the Begin Liveness data models defined in [begin_liveness_model.kt].
 *
 * Tests JSON deserialization via Gson (the project's chosen converter) and verifies
 * data class structure, default values, and backward compatibility.
 *
 * Scenarios covered:
 *  1. BeginLivenessCredentials: full deserialization from JSON
 *  2. BeginLivenessCredentials: null expiration defaults correctly
 *  3. BeginLivenessCredentials: all fields mapped to correct @SerializedName keys
 *  4. BeginLivenessData: full deserialization with credentials and region
 *  5. BeginLivenessData: backward compat - missing region defaults to null
 *  6. BeginLivenessData: backward compat - missing credentials defaults to null
 *  7. BeginLivenessData: aws_session_id mapped via @SerializedName
 *  8. BeginLivenessResponse: full deserialization
 *  9. LivenessResultResponse: full deserialization with nested objects
 * 10. LivenessResultResponse: handles null audit images and reference image
 * 11. Data class equality and copy semantics
 */
package com.example.veritypro_sdk.services

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class BeginLivenessModelTest {

    private val gson = Gson()

    // ========================================================================
    // BeginLivenessCredentials: deserialization
    // ========================================================================

    @Test
    fun `BeginLivenessCredentials deserializes all fields from JSON`() {
        val json = """
        {
            "accessKeyId": "AKIAIOSFODNN7EXAMPLE",
            "secretAccessKey": "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            "sessionToken": "FwoGZXIvYXdzEBY...",
            "expiration": "2025-06-15T12:00:00Z"
        }
        """.trimIndent()

        val creds = gson.fromJson(json, BeginLivenessCredentials::class.java)

        assertEquals("AKIAIOSFODNN7EXAMPLE", creds.accessKeyId)
        assertEquals("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", creds.secretAccessKey)
        assertEquals("FwoGZXIvYXdzEBY...", creds.sessionToken)
        assertEquals("2025-06-15T12:00:00Z", creds.expiration)
    }

    @Test
    fun `BeginLivenessCredentials expiration defaults to null when missing in JSON`() {
        val json = """
        {
            "accessKeyId": "AKID",
            "secretAccessKey": "SECRET",
            "sessionToken": "TOKEN"
        }
        """.trimIndent()

        val creds = gson.fromJson(json, BeginLivenessCredentials::class.java)

        assertEquals("AKID", creds.accessKeyId)
        assertEquals("SECRET", creds.secretAccessKey)
        assertEquals("TOKEN", creds.sessionToken)
        assertNull("Expiration should be null when not in JSON", creds.expiration)
    }

    @Test
    fun `BeginLivenessCredentials expiration can be explicitly null`() {
        val json = """
        {
            "accessKeyId": "AKID",
            "secretAccessKey": "SECRET",
            "sessionToken": "TOKEN",
            "expiration": null
        }
        """.trimIndent()

        val creds = gson.fromJson(json, BeginLivenessCredentials::class.java)

        assertNull("Expiration should be null when explicitly null", creds.expiration)
    }

    @Test
    fun `BeginLivenessCredentials constructor default expiration is null`() {
        val creds = BeginLivenessCredentials(
            accessKeyId = "key",
            secretAccessKey = "secret",
            sessionToken = "token"
        )

        assertNull("Default expiration should be null", creds.expiration)
    }

    @Test
    fun `BeginLivenessCredentials serializes back to correct JSON keys`() {
        val creds = BeginLivenessCredentials(
            accessKeyId = "AKID",
            secretAccessKey = "SECRET",
            sessionToken = "TOKEN",
            expiration = "2025-01-01T00:00:00Z"
        )

        val json = gson.toJson(creds)
        assertTrue("JSON should contain accessKeyId key", json.contains("\"accessKeyId\""))
        assertTrue("JSON should contain secretAccessKey key", json.contains("\"secretAccessKey\""))
        assertTrue("JSON should contain sessionToken key", json.contains("\"sessionToken\""))
        assertTrue("JSON should contain expiration key", json.contains("\"expiration\""))
    }

    // ========================================================================
    // BeginLivenessData: deserialization
    // ========================================================================

    @Test
    fun `BeginLivenessData deserializes with all fields including credentials and region`() {
        val json = """
        {
            "id": "liveness-123",
            "aws_session_id": "aws-sess-456",
            "status": "CREATED",
            "region": "ap-southeast-2",
            "credentials": {
                "accessKeyId": "AKID",
                "secretAccessKey": "SECRET",
                "sessionToken": "TOKEN",
                "expiration": "2025-06-15T12:00:00Z"
            }
        }
        """.trimIndent()

        val data = gson.fromJson(json, BeginLivenessData::class.java)

        assertEquals("liveness-123", data.id)
        assertEquals("aws-sess-456", data.awsSessionId)
        assertEquals("CREATED", data.status)
        assertEquals("ap-southeast-2", data.region)
        assertNotNull("Credentials should not be null", data.credentials)
        assertEquals("AKID", data.credentials!!.accessKeyId)
        assertEquals("SECRET", data.credentials.secretAccessKey)
        assertEquals("TOKEN", data.credentials.sessionToken)
        assertEquals("2025-06-15T12:00:00Z", data.credentials.expiration)
    }

    @Test
    fun `BeginLivenessData backward compat - missing region defaults to null`() {
        val json = """
        {
            "id": "liveness-old",
            "aws_session_id": "aws-sess-old",
            "status": "CREATED"
        }
        """.trimIndent()

        val data = gson.fromJson(json, BeginLivenessData::class.java)

        assertNull("Region should be null for backward compat", data.region)
    }

    @Test
    fun `BeginLivenessData backward compat - missing credentials defaults to null`() {
        val json = """
        {
            "id": "liveness-old",
            "aws_session_id": "aws-sess-old",
            "status": "CREATED"
        }
        """.trimIndent()

        val data = gson.fromJson(json, BeginLivenessData::class.java)

        assertNull("Credentials should be null for backward compat", data.credentials)
    }

    @Test
    fun `BeginLivenessData backward compat - both region and credentials missing`() {
        val json = """
        {
            "id": "liveness-v1",
            "aws_session_id": "aws-old-session",
            "status": "IN_PROGRESS"
        }
        """.trimIndent()

        val data = gson.fromJson(json, BeginLivenessData::class.java)

        assertEquals("liveness-v1", data.id)
        assertEquals("aws-old-session", data.awsSessionId)
        assertEquals("IN_PROGRESS", data.status)
        assertNull(data.region)
        assertNull(data.credentials)
    }

    @Test
    fun `BeginLivenessData aws_session_id maps via SerializedName annotation`() {
        // Verify the JSON key is "aws_session_id" not "awsSessionId"
        val json = """{"aws_session_id": "test-session-from-json"}"""
        val data = gson.fromJson(json, BeginLivenessData::class.java)

        assertEquals("test-session-from-json", data.awsSessionId)
    }

    @Test
    fun `BeginLivenessData with null awsSessionId deserializes correctly`() {
        val json = """
        {
            "id": "liveness-null-session",
            "aws_session_id": null,
            "status": "ERROR"
        }
        """.trimIndent()

        val data = gson.fromJson(json, BeginLivenessData::class.java)

        assertNull("awsSessionId should be null", data.awsSessionId)
    }

    @Test
    fun `BeginLivenessData constructor defaults for region and credentials`() {
        val data = BeginLivenessData(
            id = "id-1",
            awsSessionId = "aws-1",
            status = "CREATED"
        )

        assertNull("Default region should be null", data.region)
        assertNull("Default credentials should be null", data.credentials)
    }

    // ========================================================================
    // BeginLivenessResponse: deserialization
    // ========================================================================

    @Test
    fun `BeginLivenessResponse deserializes with nested data`() {
        val json = """
        {
            "statusCode": 200,
            "statusMessage": "Success",
            "data": {
                "id": "resp-liveness-1",
                "aws_session_id": "resp-aws-session",
                "status": "CREATED",
                "region": "us-west-2",
                "credentials": {
                    "accessKeyId": "RESP_AKID",
                    "secretAccessKey": "RESP_SECRET",
                    "sessionToken": "RESP_TOKEN",
                    "expiration": "2025-12-31T23:59:59Z"
                }
            },
            "error": null
        }
        """.trimIndent()

        val response = gson.fromJson(json, BeginLivenessResponse::class.java)

        assertEquals(200, response.statusCode)
        assertEquals("Success", response.statusMessage)
        assertNotNull(response.data)
        assertEquals("resp-aws-session", response.data!!.awsSessionId)
        assertEquals("us-west-2", response.data.region)
        assertNotNull(response.data.credentials)
        assertNull(response.error)
    }

    @Test
    fun `BeginLivenessResponse deserializes with error and no data`() {
        val json = """
        {
            "statusCode": 500,
            "statusMessage": "Internal Server Error",
            "data": null,
            "error": {"message": "Session not found"}
        }
        """.trimIndent()

        val response = gson.fromJson(json, BeginLivenessResponse::class.java)

        assertEquals(500, response.statusCode)
        assertNull(response.data)
        assertNotNull(response.error)
        assertEquals("Session not found", response.error!!["message"])
    }

    // ========================================================================
    // LivenessResultResponse: deserialization
    // ========================================================================

    @Test
    fun `LivenessResultResponse deserializes with all fields`() {
        val json = """
        {
            "Confidence": 99.8,
            "Status": "SUCCEEDED",
            "AuditImages": [
                {
                    "S3Object": {"Bucket": "my-bucket", "Name": "audit-1.jpg"},
                    "BoundingBox": {"Width": 0.5, "Height": 0.5, "Left": 0.25, "Top": 0.25}
                }
            ],
            "ReferenceImage": {
                "S3Object": {"Bucket": "ref-bucket", "Name": "reference.jpg"},
                "BoundingBox": {"Width": 0.6, "Height": 0.6, "Left": 0.2, "Top": 0.2}
            }
        }
        """.trimIndent()

        val response = gson.fromJson(json, LivenessResultResponse::class.java)

        assertEquals(99.8, response.confidence, 0.01)
        assertEquals("SUCCEEDED", response.status)
        assertNotNull(response.auditImages)
        assertEquals(1, response.auditImages!!.size)
        assertEquals("my-bucket", response.auditImages[0].s3Object?.bucket)
        assertEquals("audit-1.jpg", response.auditImages[0].s3Object?.name)
        assertNotNull(response.referenceImage)
        assertEquals("ref-bucket", response.referenceImage!!.s3Object?.bucket)
    }

    @Test
    fun `LivenessResultResponse handles null audit images and reference image`() {
        val json = """
        {
            "Confidence": 85.5,
            "Status": "SUCCEEDED",
            "AuditImages": null,
            "ReferenceImage": null
        }
        """.trimIndent()

        val response = gson.fromJson(json, LivenessResultResponse::class.java)

        assertEquals(85.5, response.confidence, 0.01)
        assertEquals("SUCCEEDED", response.status)
        assertNull(response.auditImages)
        assertNull(response.referenceImage)
    }

    @Test
    fun `LivenessResultResponse with empty audit images list`() {
        val json = """
        {
            "Confidence": 90.0,
            "Status": "SUCCEEDED",
            "AuditImages": [],
            "ReferenceImage": null
        }
        """.trimIndent()

        val response = gson.fromJson(json, LivenessResultResponse::class.java)

        assertNotNull(response.auditImages)
        assertTrue("Audit images should be empty list", response.auditImages!!.isEmpty())
    }

    @Test
    fun `LivenessResultResponse with FAILED status`() {
        val json = """
        {
            "Confidence": 12.3,
            "Status": "FAILED"
        }
        """.trimIndent()

        val response = gson.fromJson(json, LivenessResultResponse::class.java)

        assertEquals(12.3, response.confidence, 0.01)
        assertEquals("FAILED", response.status)
    }

    // ========================================================================
    // Data class equality and copy
    // ========================================================================

    @Test
    fun `BeginLivenessCredentials data class equality works correctly`() {
        val creds1 = BeginLivenessCredentials("key", "secret", "token", "2025-01-01T00:00:00Z")
        val creds2 = BeginLivenessCredentials("key", "secret", "token", "2025-01-01T00:00:00Z")
        val creds3 = BeginLivenessCredentials("different", "secret", "token", "2025-01-01T00:00:00Z")

        assertEquals("Same values should be equal", creds1, creds2)
        assertTrue("Different values should not be equal", creds1 != creds3)
    }

    @Test
    fun `BeginLivenessData data class equality works correctly`() {
        val data1 = BeginLivenessData("id", "aws", "CREATED", "us-east-1", null)
        val data2 = BeginLivenessData("id", "aws", "CREATED", "us-east-1", null)
        val data3 = BeginLivenessData("id", "different-aws", "CREATED", "us-east-1", null)

        assertEquals("Same values should be equal", data1, data2)
        assertTrue("Different awsSessionId should not be equal", data1 != data3)
    }

    @Test
    fun `BeginLivenessCredentials copy with modified expiration`() {
        val original = BeginLivenessCredentials("key", "secret", "token", "2025-01-01T00:00:00Z")
        val modified = original.copy(expiration = "2025-12-31T23:59:59Z")

        assertEquals("key", modified.accessKeyId)
        assertEquals("secret", modified.secretAccessKey)
        assertEquals("token", modified.sessionToken)
        assertEquals("2025-12-31T23:59:59Z", modified.expiration)
    }

    @Test
    fun `BeginLivenessData copy with added credentials`() {
        val original = BeginLivenessData("id", "aws", "CREATED", null, null)
        val creds = BeginLivenessCredentials("key", "secret", "token")
        val modified = original.copy(credentials = creds, region = "eu-west-1")

        assertEquals("id", modified.id)
        assertEquals("aws", modified.awsSessionId)
        assertNotNull(modified.credentials)
        assertEquals("eu-west-1", modified.region)
    }

    // ========================================================================
    // Nested model deserialization
    // ========================================================================

    @Test
    fun `LivenessBoundingBox deserializes correctly`() {
        val json = """{"Width": 0.5, "Height": 0.6, "Left": 0.1, "Top": 0.2}"""
        val box = gson.fromJson(json, LivenessBoundingBox::class.java)

        assertEquals(0.5, box.width!!, 0.001)
        assertEquals(0.6, box.height!!, 0.001)
        assertEquals(0.1, box.left!!, 0.001)
        assertEquals(0.2, box.top!!, 0.001)
    }

    @Test
    fun `LivenessS3Object deserializes correctly`() {
        val json = """{"Bucket": "test-bucket", "Name": "test-image.jpg"}"""
        val s3Obj = gson.fromJson(json, LivenessS3Object::class.java)

        assertEquals("test-bucket", s3Obj.bucket)
        assertEquals("test-image.jpg", s3Obj.name)
    }

    @Test
    fun `LivenessAuditImage deserializes with nested objects`() {
        val json = """
        {
            "S3Object": {"Bucket": "audit-bucket", "Name": "audit.jpg"},
            "BoundingBox": {"Width": 0.3, "Height": 0.4, "Left": 0.1, "Top": 0.2}
        }
        """.trimIndent()

        val auditImage = gson.fromJson(json, LivenessAuditImage::class.java)

        assertNotNull(auditImage.s3Object)
        assertEquals("audit-bucket", auditImage.s3Object!!.bucket)
        assertNotNull(auditImage.boundingBox)
        assertEquals(0.3, auditImage.boundingBox!!.width!!, 0.001)
    }

    @Test
    fun `LivenessAuditImage handles null nested objects`() {
        val json = """{"S3Object": null, "BoundingBox": null}"""
        val auditImage = gson.fromJson(json, LivenessAuditImage::class.java)

        assertNull(auditImage.s3Object)
        assertNull(auditImage.boundingBox)
    }
}
